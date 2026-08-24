package com.cortesnotetaker.app.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WhisperEngine {
    private var nativeContext: Long = 0
    private var isInitialized = false
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var modelPath: String? = null

    external fun nativeInit(modelPath: String): Long
    external fun nativeTranscribe(ctxPtr: Long, pcmData: FloatArray, language: String): WhisperResult?
    external fun nativeRelease(ctxPtr: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    suspend fun initialize(context: Context): Boolean {
        return withContext(dispatcher) {
            if (isInitialized && nativeContext != 0L) return@withContext true
            
            modelPath = getModelPath(context)
            if (modelPath == null) {
                Log.e("WhisperEngine", "Whisper model not found in assets or storage")
                return@withContext false
            }
            
            nativeContext = nativeInit(modelPath!!)
            isInitialized = nativeContext != 0L
            
            if (isInitialized) {
                Log.d("WhisperEngine", "Whisper initialized successfully with model: $modelPath")
            } else {
                Log.e("WhisperEngine", "Failed to initialize native whisper context")
            }
            isInitialized
        }
    }

    private fun getModelPath(context: Context): String? {
        val modelFile = File(context.filesDir, "ggml-base.bin")
        if (modelFile.exists() && modelFile.length() > 10000000L) {
            return modelFile.absolutePath
        }
        
        return copyModelFromAssets(context, modelFile)
    }

    private fun copyModelFromAssets(context: Context, targetFile: File): String? {
        try {
            val inputStream = context.assets.open("whisper/ggml-base.bin")
            val outputStream = FileOutputStream(targetFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Log.d("WhisperEngine", "Model copied to: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e("WhisperEngine", "Failed to copy model from assets", e)
            return null
        }
    }

    suspend fun transcribe(pcmData: ShortArray, language: String = "auto"): WhisperResult? {
        return withContext(dispatcher) {
            if (!isInitialized || nativeContext == 0L) {
                Log.e("WhisperEngine", "Whisper not initialized")
                return@withContext null
            }
            
            val floatData = FloatArray(pcmData.size) { i -> pcmData[i].toFloat() / 32768.0f }
            val result = nativeTranscribe(nativeContext, floatData, language)
            
            if (result != null) {
                Log.d("WhisperEngine", "Transcribed text: '${result.text}', logprob: ${result.avgLogProb}")
            }
            
            result
        }
    }

    fun release() {
        if (isInitialized && nativeContext != 0L) {
            nativeRelease(nativeContext)
            nativeContext = 0
            isInitialized = false
            Log.d("WhisperEngine", "Whisper native engine released")
        }
    }

    fun isReady(): Boolean = isInitialized && nativeContext != 0L
}