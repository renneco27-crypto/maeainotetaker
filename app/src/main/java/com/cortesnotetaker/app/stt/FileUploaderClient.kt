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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

data class ImportedSegmentResult(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class JobStatusResult(
    val status: String, // "processing", "completed", "error"
    val progress: Int = 0,
    val text: String? = null,
    val segments: List<ImportedSegmentResult> = emptyList()
)

class FileUploaderClient(private val context: Context) {
    
    private val localServerUrl = com.cortesnotetaker.app.BuildConfig.LOCAL_SERVER_URL
    
    // Upload client needs longer timeouts for massive files
    private val tunnelInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Bypass-Tunnel-Reminder", "true")
            .build()
        chain.proceed(request)
    }

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .addInterceptor(tunnelInterceptor)
        .build()

    // Poll client is fast
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(tunnelInterceptor)
        .build()

    private fun getCandidateBaseUrls(): List<String> {
        val rawCandidates = mutableListOf<String>()
        rawCandidates.addAll(listOf(
            localServerUrl,
            "http://192.168.1.4:8000/transcribe",
            "http://10.218.142.107:8000/transcribe",
            "http://192.168.137.1:8000/transcribe",
            "http://192.168.43.1:8000/transcribe",
            "https://cortes-notetaker.loca.lt/transcribe"
        ))
        if (com.cortesnotetaker.app.stt.AppSettings.customTunnelUrl.isNotBlank()) {
            val url = com.cortesnotetaker.app.stt.AppSettings.customTunnelUrl
            rawCandidates.add(if (url.endsWith("/transcribe")) url else "$url/transcribe")
        }
        return rawCandidates.filter { it.isNotBlank() }.distinct().map {
            if (it.endsWith("/transcribe")) it.substringBeforeLast("/transcribe") else it.removeSuffix("/")
        }
    }

    companion object {
        val hfCompletedJobs = java.util.concurrent.ConcurrentHashMap<String, JobStatusResult>()
    }

    /**
     * Uploads the audio file to the PC and returns the jobId immediately.
     */
    suspend fun uploadAudioFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("FileUploader", "Preparing file from URI: $uri")
            val tempFile = File(context.cacheDir, "shared_upload.media")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e("FileUploader", "Temp file is missing or empty")
                return@withContext null
            }

            Log.d("FileUploader", "File prepared (${tempFile.length()} bytes). Uploading...")
            
            // 1. Try Hugging Face Cloud directly FIRST
            val hfJobId = uploadToHuggingFaceDirectlyAsync(tempFile)
            if (hfJobId != null) {
                tempFile.delete()
                return@withContext hfJobId
            }
            
            // 2. Fallback to Local PC Server

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", 
                    tempFile.name, 
                    tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()

            val baseUrls = getCandidateBaseUrls()
            for (baseUrl in baseUrls) {
                val uploadUrl = "$baseUrl/transcribe_file"
                try {
                    Log.d("FileUploader", "Attempting upload to: $uploadUrl")
                    val request = Request.Builder()
                        .url(uploadUrl)
                        .post(requestBody)
                        .build()

                    val response = uploadClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val jsonObject = JSONObject(responseBody)
                        val jobId = jsonObject.optString("job_id", "")
                        if (jobId.isNotBlank()) {
                            Log.d("FileUploader", "Upload success to $uploadUrl. Job ID: $jobId")
                            tempFile.delete()
                            return@withContext jobId
                        }
                    } else {
                        Log.e("FileUploader", "Server rejected upload at $uploadUrl with code ${response.code}: ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e("FileUploader", "CRITICAL UPLOAD ERROR to $uploadUrl: ${e.javaClass.simpleName} - ${e.message}", e)
                }
            }
            
            tempFile.delete()
            return@withContext null
        } catch (e: Exception) {
            Log.e("FileUploader", "Exception during upload", e)
            return@withContext null
        }
    }

    /**
     * Checks the job status for a given jobId.
     */
    suspend fun checkJobStatus(jobId: String): JobStatusResult? = withContext(Dispatchers.IO) {
        if (hfCompletedJobs.containsKey(jobId)) {
            return@withContext hfCompletedJobs[jobId]
        }
        
        val baseUrls = getCandidateBaseUrls()
        for (baseUrl in baseUrls) {
            val statusUrl = "$baseUrl/job_status/$jobId"
            try {
                val statusReq = Request.Builder().url(statusUrl).get().build()
                val statusRes = pollClient.newCall(statusReq).execute()
                
                if (statusRes.isSuccessful) {
                    val statusBody = statusRes.body?.string() ?: ""
                    val statusJson = JSONObject(statusBody)
                    val status = statusJson.optString("status", "error")
                    val progress = statusJson.optInt("progress", 0)
                    val text = statusJson.optString("text", null)
                    
                    val segmentsList = mutableListOf<ImportedSegmentResult>()
                    val segmentsArray = statusJson.optJSONArray("segments")
                    if (segmentsArray != null) {
                        for (i in 0 until segmentsArray.length()) {
                            val segObj = segmentsArray.getJSONObject(i)
                            val sMs = segObj.optLong("start_ms", 0L)
                            val eMs = segObj.optLong("end_ms", 0L)
                            val segText = segObj.optString("text", "")
                            if (segText.isNotBlank()) {
                                segmentsList.add(ImportedSegmentResult(sMs, eMs, segText))
                            }
                        }
                    }
                    
                    return@withContext JobStatusResult(
                        status = status,
                        progress = progress,
                        text = if (text == "null" || text.isNullOrBlank()) null else text,
                        segments = segmentsList
                    )
                } else if (statusRes.code == 404) {
                    Log.w("FileUploader", "Server returned 404. Job $jobId no longer exists. Aborting poll.")
                    return@withContext JobStatusResult(status = "error")
                }
            } catch (e: Exception) {
                // Ignore and try next candidate
            }
        }
        return@withContext null
    }

    /**
     * Tells the PC server to immediately abort and halt transcription for this job.
     */
    suspend fun cancelJob(jobId: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrls = getCandidateBaseUrls()
        val emptyBody = okhttp3.RequestBody.create(null, ByteArray(0))
        for (baseUrl in baseUrls) {
            val cancelUrl = "$baseUrl/cancel_job/$jobId"
            try {
                val request = Request.Builder().url(cancelUrl).post(emptyBody).build()
                val response = pollClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("FileUploader", "Successfully notified PC to cancel Job: $jobId")
                    return@withContext true
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return@withContext false
    }

    private suspend fun uploadToHuggingFaceDirectlyAsync(tempFile: File): String? = withContext(Dispatchers.IO) {
        val hfBase = "https://alibaba2304-lectaaa.hf.space"
        try {
            Log.d("FileUploader", "Attempting direct upload to Hugging Face...")
            
            // STEP 1: Upload
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", tempFile.name, tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
                
            val uploadReq = Request.Builder().url("$hfBase/upload").post(uploadBody).build()
            val uploadRes = uploadClient.newCall(uploadReq).execute()
            if (!uploadRes.isSuccessful) return@withContext null
            
            val uploadJson = org.json.JSONArray(uploadRes.body?.string() ?: "[]")
            if (uploadJson.length() == 0) return@withContext null
            val uploadedPath = uploadJson.getString(0)
            
            Log.d("FileUploader", "HF Upload Success. Path: \$uploadedPath")
            
            // STEP 2: Submit Job
            val predictBodyStr = "{\"data\": [{\"path\": \"\$uploadedPath\"}]}"
            val predictBody = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), predictBodyStr)
            val predictReq = Request.Builder().url("$hfBase/call/predict").post(predictBody).build()
            val predictRes = uploadClient.newCall(predictReq).execute()
            
            if (!predictRes.isSuccessful) return@withContext null
            
            val predictResJson = JSONObject(predictRes.body?.string() ?: "{}")
            val eventId = predictResJson.optString("event_id", "")
            if (eventId.isEmpty()) return@withContext null
            
            Log.d("FileUploader", "HF Job Submitted. Event ID: \$eventId")
            
            // Register job
            hfCompletedJobs[eventId] = JobStatusResult(status = "processing", progress = 50)
            
            // STEP 3: Listen to SSE Stream in background
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                listenToHfSseStream(hfBase, eventId)
            }
            
            return@withContext eventId
            
        } catch (e: Exception) {
            Log.e("FileUploader", "HF Direct failed", e)
        }
        return@withContext null
    }
    
    private fun listenToHfSseStream(hfBase: String, eventId: String) {
        try {
            val sseClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                
            val req = Request.Builder().url("$hfBase/call/predict/$eventId").get().build()
            sseClient.newCall(req).execute().use { response ->
                val inputStream = response.body?.byteStream() ?: return
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val dataStr = line!!.substring(6)
                        try {
                            val json = JSONObject(dataStr)
                            val msg = json.optString("msg")
                            if (msg == "process_completed") {
                                val outputObj = json.optJSONObject("output")
                                val dataArr = outputObj?.optJSONArray("data")
                                val text = dataArr?.optString(0, "")
                                
                                val segments = mutableListOf<ImportedSegmentResult>()
                                if (!text.isNullOrBlank()) {
                                    segments.add(ImportedSegmentResult(0, 1000000, text))
                                }
                                
                                hfCompletedJobs[eventId] = JobStatusResult(
                                    status = "completed",
                                    progress = 100,
                                    text = text,
                                    segments = segments
                                )
                                Log.d("FileUploader", "HF Job Completed: \$eventId")
                                return
                            }
                        } catch (e: Exception) {
                            // ignore json parse error for heartbeat
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileUploader", "HF SSE Stream failed", e)
            hfCompletedJobs[eventId] = JobStatusResult(status = "error")
        }
    }
}
