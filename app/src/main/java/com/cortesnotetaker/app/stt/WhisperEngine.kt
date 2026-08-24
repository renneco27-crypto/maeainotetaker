package com.cortesnotetaker.app.stt

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WhisperEngine {
    private var nativeContext: Long = 0
    private var isInitialized = false
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    // For Play Asset Delivery / sideload fallback
    private var modelPath: String? = null

    external fun nativeInit(modelPath: String): Long
    external fun nativeTranscribe(pcmData: FloatArray, language: String): WhisperResult?
    external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    suspend fun initialize(context: Context): Boolean {
        return withContext(dispatcher) {
            if (isInitialized) return@withContext true
            
            modelPath = getModelPath(context)
            if (modelPath == null) {
                Log.e("WhisperEngine", "Model not found")
                return@withContext false
            }
            
            nativeContext = nativeInit(modelPath!!)
            isInitialized = nativeContext != 0L
            
            if (isInitialized) {
                Log.d("WhisperEngine", "Initialized with model: $modelPath")
            } else {
                Log.e("WhisperEngine", "Failed to initialize native context")
            }
            isInitialized
        }
    }

    private fun getModelPath(context: Context): String? {
        // Try Play Asset Delivery first
        try {
            val assetPackManager = com.google.android.play.assetdelivery.AssetPackManagerFactory.create(context)
            val assetPackName = "whisperModelPack"
            val request = com.google.android.play.assetdelivery.AssetPackManager.RequestInfo().apply {
                assetPackName
            }
            // Check if asset pack is available
            val assetPackStates = assetPackManager.getPackStates(listOf(assetPackName)).await()
            val state = assetPackStates[assetPackName]
            if (state != null && state.status() == com.google.android.play.assetdelivery.AssetPackState.STATUS_COMPLETED) {
                val assets = assetPackManager.getAssets(assetPackName)
                val asset = assets.getAsset("whisper/ggml-base.bin")
                val file = File(asset.getAssetFileDescriptor().getFileDescriptor())
                if (file.exists()) {
                    return file.absolutePath
                }
            }
        } catch (e: Exception) {
            // Asset delivery not available or failed, fall back to assets/
            Log.d("WhisperEngine", "Asset delivery not available: ${e.message}")
        }

        // Fallback: copy from assets/ (for sideload APK)
        val modelFile = File(context.filesDir, "ggml-base.bin")
        if (modelFile.exists()) {
            return modelFile.absolutePath
        }
        
        // Copy from assets
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
                Log.e("WhisperEngine", "Not initialized")
                return@withContext null
            }
            
            // Convert ShortArray to FloatArray (normalized)
            val floatData = pcmData.map { it.toFloat() / 32768f }.toFloatArray()
            
            val result = nativeTranscribe(floatData, language)
            
            if (result != null) {
                // Check confidence
                if (result.avgLogProb < -1.0f) {
                    // Low confidence - mark as unclear in the text
                    result.text = if (result.text.isNotBlank()) "[unclear]" else "[unclear]"
                }
            }
            
            result
        }
    }

    fun release() {
        if (isInitialized && nativeContext != 0L) {
            nativeRelease()
            nativeContext = 0
            isInitialized = false
            Log.d("WhisperEngine", "Released")
        }
    }

    fun isReady(): Boolean = isInitialized
}