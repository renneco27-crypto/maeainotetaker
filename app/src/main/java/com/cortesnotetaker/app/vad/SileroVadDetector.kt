package com.cortesnotetaker.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
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
    private val SPEECH_THRESHOLD = 0.35f
    private val SILENCE_THRESHOLD = 0.15f
    private val SILENCE_HANGOVER_MS = 400L // 400ms silence before concluding speech segment
    private val MAX_SPEECH_DURATION_MS = 15000L // 15 seconds max segment
    private val MIN_SPEECH_DURATION_MS = 250L // Minimum 250ms speech for Whisper
    
    private var silenceStartMs: Long = 0L

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
            Log.d("SileroVAD", "Model loaded successfully from: $modelPath")
        } catch (e: Exception) {
            Log.e("SileroVAD", "Failed to initialize Silero VAD model", e)
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

    private val CONTEXT_SIZE = 64
    private val contextBuffer = FloatArray(CONTEXT_SIZE)

    fun processPcmFrame(pcmData: ShortArray): SpeechSegment? {
        val floatData = FloatArray(pcmData.size) { i -> pcmData[i].toFloat() / 32768.0f }
        val frame = FloatArray(512)
        val copySize = minOf(floatData.size, 512)
        System.arraycopy(floatData, 0, frame, 0, copySize)
        
        val pcmFrame = ShortArray(512)
        System.arraycopy(pcmData, 0, pcmFrame, 0, copySize)

        // Prepend 64-sample context -> 576 input samples for Silero VAD v5
        val modelInput = FloatArray(CONTEXT_SIZE + 512)
        System.arraycopy(contextBuffer, 0, modelInput, 0, CONTEXT_SIZE)
        System.arraycopy(frame, 0, modelInput, CONTEXT_SIZE, 512)

        // Update context with last 64 samples of current frame
        System.arraycopy(frame, 512 - CONTEXT_SIZE, contextBuffer, 0, CONTEXT_SIZE)

        return runInference(modelInput, pcmFrame)
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
            inputTensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 576))

            val stateBuffer = FloatBuffer.wrap(state)
            stateTensor = OnnxTensor.createTensor(env, stateBuffer, longArrayOf(2, 1, 128))

            val srBuffer = LongBuffer.wrap(longArrayOf(sampleRate))
            srTensor = OnnxTensor.createTensor(env, srBuffer, longArrayOf())

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
            silenceStartMs = 0L
            if (!isInSpeech) {
                isInSpeech = true
                accumulatedSpeechStartMs = currentTimeMs
                accumulatedSpeechFrames.clear()
            }
            lastSpeechEndMs = currentTimeMs
            accumulatedSpeechFrames.addAll(pcmFrame.toList())
        } else {
            if (isInSpeech) {
                accumulatedSpeechFrames.addAll(pcmFrame.toList())
                if (silenceStartMs == 0L) {
                    silenceStartMs = currentTimeMs
                }
                
                val silenceDuration = currentTimeMs - silenceStartMs
                val totalSpeechDuration = currentTimeMs - accumulatedSpeechStartMs

                if (silenceDuration >= SILENCE_HANGOVER_MS || totalSpeechDuration >= MAX_SPEECH_DURATION_MS) {
                    val speechDuration = lastSpeechEndMs - accumulatedSpeechStartMs
                    if (speechDuration >= MIN_SPEECH_DURATION_MS && accumulatedSpeechFrames.isNotEmpty()) {
                        val pcmData = accumulatedSpeechFrames.toShortArray()
                        val segment = SpeechSegment(pcmData, accumulatedSpeechStartMs, lastSpeechEndMs)
                        accumulatedSpeechFrames.clear()
                        isInSpeech = false
                        silenceStartMs = 0L
                        speechSegmentChannel.trySend(segment)
                        return segment
                    }
                    accumulatedSpeechFrames.clear()
                    isInSpeech = false
                    silenceStartMs = 0L
                }
            }
        }
        return null
    }

    fun reset() {
        state = FloatArray(2 * 1 * 128)
        java.util.Arrays.fill(contextBuffer, 0f)
        accumulatedSpeechFrames.clear()
        isInSpeech = false
        silenceStartMs = 0L
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