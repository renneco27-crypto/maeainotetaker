package com.cortesnotetaker.app.stt

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

class FileUploaderClient(private val context: Context) {
    
    private val localServerUrl = com.cortesnotetaker.app.BuildConfig.LOCAL_SERVER_URL
    
    // Upload client needs longer timeouts for massive files
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS) // 10 minutes to upload a giant file
        .build()

    // Poll client is fast
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun uploadAndTranscribe(uri: Uri, onProgress: (String) -> Unit): String? {
        if (localServerUrl.isBlank()) {
            Log.e("FileUploader", "Local PC server URL is not configured")
            return null
        }
        
        try {
            // 1. Copy the shared URI to a temporary local file so OkHttp can read it
            onProgress("Preparing file...")
            val tempFile = File(context.cacheDir, "shared_upload.media")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            if (!tempFile.exists()) return null

            // 2. Upload the file to POST /transcribe_file
            onProgress("Uploading to PC...")
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", 
                    tempFile.name, 
                    tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()

            val uploadUrl = if (localServerUrl.endsWith("/transcribe")) {
                localServerUrl.replace("/transcribe", "/transcribe_file")
            } else {
                "$localServerUrl/transcribe_file"
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            val response = uploadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("FileUploader", "Upload failed: ${response.code}")
                return null
            }

            val responseBody = response.body?.string() ?: return null
            val jsonObject = JSONObject(responseBody)
            val jobId = jsonObject.optString("job_id", "")
            
            if (jobId.isBlank()) return null
            
            // 3. Poll for status
            val statusUrl = uploadUrl.replace("transcribe_file", "job_status/$jobId")
            
            while (true) {
                onProgress("PC is transcribing at 1.5x speed...")
                delay(3000) // Poll every 3 seconds
                
                val statusReq = Request.Builder().url(statusUrl).get().build()
                val statusRes = pollClient.newCall(statusReq).execute()
                
                if (statusRes.isSuccessful) {
                    val statusBody = statusRes.body?.string() ?: ""
                    val statusJson = JSONObject(statusBody)
                    val status = statusJson.optString("status")
                    
                    when (status) {
                        "completed" -> {
                            tempFile.delete()
                            return statusJson.optString("text")
                        }
                        "error" -> {
                            val err = statusJson.optString("error")
                            Log.e("FileUploader", "PC Job Error: $err")
                            tempFile.delete()
                            return null
                        }
                        "processing" -> {
                            // Keep waiting
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("FileUploader", "Exception during upload", e)
            return null
        }
    }
}
