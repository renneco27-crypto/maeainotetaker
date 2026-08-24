package com.cortesnotetaker.app.vad

import android.content.Context
import android.util.Log
import com.microsoft.onnxruntime.OnnxTensor
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class SileroVadDetector(private val context: Context) {
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    // Silero VAD state (2 layers * 1 batch * 128 hidden)
    private var state: FloatArray = FloatArray(2 * 1 * 128)
    private val sampleRate: Long = 16000L
    
    private var accumulatedSpeechFrames: MutableList<Short> = mutableListOf()
    private var accumulatedSpeechStartMs: Long = 0
    private var lastSpeechEndMs: Long = 0
    private var isInSpeech = false
    
    // Thresholds for hysteresis
    private val SPEECH_THRESHOLD = 0.5f
    private val SILENCE_THRESHOLD = 0.35f
    private val MAX_SPEECH_DURATION_MS = 10000 // 10 seconds max segment
    private val MIN_SPEECH_DURATION_MS = 100 // Minimum 100ms speech
    
    private val speechSegmentChannel = Channel<SpeechSegment>(Channel.UNLIMITED)
    val speechSegmentFlow: ReceiveChannel<SpeechSegment> = speechSegmentChannel

    init {
        initializeModel()
    }

    private fun initializeModel() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelPath = copyModelFromAssets()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnvironment?.createSession(modelPath, sessionOptions)
            Log.d("SileroVAD", "Model loaded from: $modelPath")
        } catch (e: Exception) {
            Log.e("SileroVAD", "Failed to initialize model", e)
        }
    }

    private fun copyModelFromAssets(): String {
        val modelFile = File(context.filesDir, "silero_vad.onnx")
        if (modelFile.exists() && modelFile.length() > 0) {
            return modelFile.absolutePath
        }
        
        context.assets.open("silero_vad.onnx").use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return modelFile.absolutePath
    }

    fun processFrame(pcmFrame: ShortArray): SpeechSegment? {
        if (ortSession == null) return null
        
        // Convert ShortArray to FloatArray (normalized to -1.0 to 1.0)
        val floatFrame = FloatArray(pcmFrame.size) { i -> pcmFrame[i] / 32768f }
        
        // Silero expects 512 samples (32ms at 16kHz)
        if (floatFrame.size != 512) {
            return processPaddedFrame(floatFrame, pcmFrame)
        }
        
        return runInference(floatFrame, pcmFrame)
    }

    private fun processPaddedFrame(input: FloatArray, originalPcm: ShortArray): SpeechSegment? {
        val frame = FloatArray(512)
        val copySize = minOf(input.size, 512)
        System.arraycopy(input, 0, frame, 0, copySize)
        
        val pcmFrame = ShortArray(512)
        System.arraycopy(originalPcm, 0, pcmFrame, 0, copySize)
        return runInference(frame, pcmFrame)
    }

    private fun runInference(frame: FloatArray, pcmFrame: ShortArray): SpeechSegment? {
        val env = ortEnvironment ?: return null
        val session = ortSession ?: return null
        
        var inputTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

        try {
            val inputBuffer = FloatBuffer.wrap(frame)
            inputTensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 512))

            val stateBuffer = FloatBuffer.wrap(state)
            stateTensor = OnnxTensor.createTensor(env, stateBuffer, longArrayOf(2, 1, 128))

            val srBuffer = LongBuffer.wrap(longArrayOf(sampleRate))
            srTensor = OnnxTensor.createTensor(env, srBuffer, longArrayOf(1))

            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )

            outputs = session.run(inputs)
            
            val outputTensor = outputs.get(0) as? OnnxTensor
            val newStateTensor = outputs.get(1) as? OnnxTensor

            val speechProb = if (outputTensor != null) {
                val floatBuffer = outputTensor.floatBuffer
                floatBuffer.get(0)
            } else 0f

            if (newStateTensor != null) {
                val stateBuf = newStateTensor.floatBuffer
                stateBuf.get(state)
            }
            
            return processSpeechProbability(speechProb, pcmFrame)
        } catch (e: Exception) {
            Log.e("SileroVAD", "Inference error", e)
            return null
        } finally {
            inputTensor?.close()
            stateTensor?.close()
            srTensor?.close()
            outputs?.close()
        }
    }

    private fun processSpeechProbability(prob: Float, pcmFrame: ShortArray): SpeechSegment? {
        val currentTimeMs = System.currentTimeMillis()
        
        if (prob > SPEECH_THRESHOLD) {
            // Speech detected
            if (!isInSpeech) {
                isInSpeech = true
                accumulatedSpeechStartMs = currentTimeMs - (FRAME_DURATION_MS * 2) // Approximate start
                accumulatedSpeechFrames.clear()
            }
            isInSpeech = true
            lastSpeechEndMs = currentTimeMs
            accumulatedSpeechFrames.addAll(pcmFrame.toList())
            
        } else if (prob < SILENCE_THRESHOLD && isInSpeech) {
            // Silence detected after speech - check if we should emit
            val speechDuration = currentTimeMs - accumulatedSpeechStartMs
            
            if (speechDuration >= MIN_SPEECH_DURATION_MS || speechDuration >= MAX_SPEECH_DURATION_MS) {
                // Emit segment
                val pcmData = accumulatedSpeechFrames.toShortArray()
                val segment = SpeechSegment(pcmData, accumulatedSpeechStartMs, currentTimeMs)
                accumulatedSpeechFrames.clear()
                isInSpeech = false
                speechSegmentChannel.trySend(segment)
                return segment
            }
            // Too short, discard
            accumulatedSpeechFrames.clear()
            isInSpeech = false
        }
        
        // Check max duration
        if (isInSpeech && (currentTimeMs - accumulatedSpeechStartMs) >= MAX_SPEECH_DURATION_MS) {
            val pcmData = accumulatedSpeechFrames.toShortArray()
            val segment = SpeechSegment(pcmData, accumulatedSpeechStartMs, currentTimeMs)
            accumulatedSpeechFrames.clear()
            isInSpeech = false
            speechSegmentChannel.trySend(segment)
            return segment
        }
        
        return null
    }

    fun reset() {
        hState = FloatArray(2 * 1 * 128)
        cState = FloatArray(2 * 1 * 128)
        accumulatedSpeechFrames.clear()
        isInSpeech = false
    }

    fun release() {
        ortSession?.close()
        ortEnvironment?.close()
        ortSession = null
        ortEnvironment = null
        speechSegmentChannel.close()
    }

    companion object {
        const val FRAME_SIZE = 512
        const val FRAME_DURATION_MS = 32
        const val SAMPLE_RATE = 16000
    }
}

data class SpeechSegment(
    val pcmData: ShortArray,
    val startMs: Long,
    val endMs: Long
)