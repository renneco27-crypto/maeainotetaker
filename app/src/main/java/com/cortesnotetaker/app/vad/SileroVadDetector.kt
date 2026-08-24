package com.cortesnotetaker.app.vad

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.microsoft.onnxruntime.OnnxTensor
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

class SileroVadDetector(private val context: Context) {
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    // Silero VAD state
    private var hState: FloatArray = FloatArray(2 * 1 * 128) // 2 layers * batch * hidden_size
    private var cState: FloatArray = FloatArray(2 * 1 * 128)
    private var srState: FloatArray = floatArrayOf(16000f) // Sample rate
    
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
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            ortSession = ortEnvironment!!.createSession(modelPath, sessionOptions)
            Log.d("SileroVAD", "Model loaded from: $modelPath")
        } catch (e: Exception) {
            Log.e("SileroVAD", "Failed to initialize model", e)
        }
    }

    private fun copyModelFromAssets(): String {
        val modelFile = File(context.filesDir, "silero_vad.onnx")
        if (modelFile.exists()) {
            return modelFile.absolutePath
        }
        
        val inputStream = context.assets.open("silero_vad.onnx")
        val outputStream = java.io.FileOutputStream(modelFile)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        return modelFile.absolutePath
    }

    fun processFrame(pcmFrame: ShortArray): SpeechSegment? {
        if (ortSession == null) return null
        
        // Convert ShortArray to FloatArray (normalized to -1.0 to 1.0)
        val floatFrame = pcmFrame.map { it.toFloat() / 32768f }.toFloatArray()
        
        // Silero expects 512 samples (32ms at 16kHz)
        if (floatFrame.size != 512) {
            // Pad or trim if needed
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
        try {
            // Create input tensors
            val inputTensor = OnnxTensor.createTensor(ortEnvironment!!, arrayOf(frame))
            val hTensor = OnnxTensor.createTensor(ortEnvironment!!, arrayOf(hState.reshape(2, 1, 128)))
            val cTensor = OnnxTensor.createTensor(ortEnvironment!!, arrayOf(cState.reshape(2, 1, 128)))
            val srTensor = OnnxTensor.createTensor(ortEnvironment!!, arrayOf(srState))

            val inputNames = arrayOf("input", "h", "c", "sr")
            val inputs = mapOf(
                inputNames[0] to inputTensor,
                inputNames[1] to hTensor,
                inputNames[2] to cTensor,
                inputNames[3] to srTensor
            )

            val outputs = ortSession!!.run(inputs)
            
            // Get output: [speech_prob, new_h, new_c]
            val speechProb = (outputs[0] as OnnxTensor).getValue() as Array<FloatArray>
            val newH = (outputs[1] as OnnxTensor).getValue() as Array<FloatArray>
            val newC = (outputs[2] as OnnxTensor).getValue() as Array<FloatArray>
            
            val prob = speechProb[0][0]
            
            // Update states
            hState = newH[0].flatten()
            cState = newC[0].flatten()
            
            // Process speech probability with hysteresis
            return processSpeechProbability(prob, pcmFrame)
            
        } catch (e: Exception) {
            Log.e("SileroVAD", "Inference error", e)
            return null
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