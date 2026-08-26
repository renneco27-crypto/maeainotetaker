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
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    // Poll client is fast
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun getCandidateBaseUrls(): List<String> {
        val rawCandidates = listOf(
            localServerUrl,
            "http://192.168.1.4:8000/transcribe",
            "http://10.218.142.107:8000/transcribe",
            "http://192.168.137.1:8000/transcribe",
            "http://192.168.43.1:8000/transcribe"
        )
        return rawCandidates.filter { it.isNotBlank() }.distinct().map {
            if (it.endsWith("/transcribe")) it.substringBeforeLast("/transcribe") else it.removeSuffix("/")
        }
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
                    }
                } catch (e: Exception) {
                    Log.w("FileUploader", "Failed to upload to $uploadUrl: ${e.message}")
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
}
