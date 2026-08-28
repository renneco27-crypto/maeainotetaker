package com.cortesnotetaker.app.stt

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

enum class TranscriptionMode { AUTO, HOTSPOT_ONLY, CLOUD_ONLY }

enum class SubjectPreset(val displayName: String, val prompt: String) {
    GENERAL(
        "General / Mixed", 
        "English, Tagalog, Bisaya, Cebuano, Filipino, lecture, classroom, discussion, professor, student."
    ),
    SCIENCE_TECH(
        "Science & Tech (Sulfur, Porcelain, etc.)", 
        "English, Tagalog, Bisaya, science, chemistry, physics, biology, technology, gunpowder, sulfur, potassium nitrate, porcelain, elements, compound, equation, experiment, laboratory, hypothesis, reaction, acid, base, molecule, porcelain glass, potash, saltpeter."
    ),
    HISTORY_SOCIAL(
        "History & Social (Egypt, China, etc.)", 
        "English, Tagalog, Bisaya, history, historical antecedents, civilization, Egypt, Mesopotamia, China, revolution, government, culture, society, artifact, ancient, renaissance, empire, pottery, dynasty, period, shadoof, papyrus, cuneiform, hieroglyphics."
    ),
    MATH_ENGINEERING(
        "Math & Engineering", 
        "English, Tagalog, Bisaya, mathematics, calculus, algebra, geometry, engineering, formula, theorem, derivative, integral, computation, matrix, variable, function, graph, vector, equation."
    ),
    BUSINESS_LAW(
        "Business, Economics & Law", 
        "English, Tagalog, Bisaya, business, economics, accounting, finance, management, law, constitution, article, liability, asset, profit, market, contract, jurisprudence."
    )
}

object AppSettings {
    var mode = TranscriptionMode.AUTO
    var customTunnelUrl = ""
    var subjectPreset = SubjectPreset.GENERAL
}

class NetworkWhisperClient {
    // Official Hugging Face Serverless Inference API for Whisper Large V3 Turbo (Fast & No ZeroGPU limits!)
    private val hfServerUrl = "https://router.huggingface.co/hf-inference/models/openai/whisper-large-v3-turbo"
    
    // Loaded securely from local.properties -> BuildConfig
    private val hfToken = com.cortesnotetaker.app.BuildConfig.HF_TOKEN
    private val localServerUrl = com.cortesnotetaker.app.BuildConfig.LOCAL_SERVER_URL

    // Standard client for Hugging Face (longer timeouts as it's cloud-based)
    private val hfClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val tunnelInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Bypass-Tunnel-Reminder", "true")
            .build()
        chain.proceed(request)
    }

    // Fast connect client for Local PC, but very long read timeout since CPU inference can take 60+ seconds
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(tunnelInterceptor)
        .build()

    fun transcribe(pcmData: ShortArray): WhisperResult? {
        val wavBytes = createWavBytes(pcmData, 48000)
        
        // 1. Try Local PC Server if configured and mode allows it
        if (AppSettings.mode != TranscriptionMode.CLOUD_ONLY) {
            val candidateUrls = mutableListOf(
                localServerUrl,
                "http://192.168.1.4:8000/transcribe",
                "http://10.218.142.107:8000/transcribe",
                "http://192.168.137.1:8000/transcribe",
                "http://192.168.43.1:8000/transcribe",
                "https://cortes-notetaker.loca.lt/transcribe"
            )
            if (AppSettings.customTunnelUrl.isNotBlank()) {
                val url = AppSettings.customTunnelUrl
                candidateUrls.add(if (url.endsWith("/transcribe")) url else "$url/transcribe")
            }
            val finalCandidateUrls = candidateUrls.filter { it.isNotBlank() }.distinct()

            for (serverUrl in finalCandidateUrls) {
                try {
                    Log.d("NetworkWhisper", "Attempting local PC server at: $serverUrl")
                    val request = Request.Builder()
                        .url(serverUrl)
                        .addHeader("Content-Type", "audio/wav")
                        .addHeader("X-Subject-Preset", AppSettings.subjectPreset.name)
                        .addHeader("X-Initial-Prompt", AppSettings.subjectPreset.prompt)
                        .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                        .build()
                        
                    val response = localClient.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val jsonObject = JSONObject(responseBody)
                        val transcript = jsonObject.optString("text", "").trim()
                        
                        Log.d("NetworkWhisper", "Local PC Transcription success ($serverUrl): '$transcript'")
                        return WhisperResult(text = transcript, avgLogProb = 0.0f)
                    } else {
                        Log.w("NetworkWhisper", "Local PC server at $serverUrl returned error (${response.code}).")
                    }
                } catch (e: Exception) {
                    Log.w("NetworkWhisper", "Local PC server at $serverUrl unreachable (${e.message}).")
                }
            }
            
            if (AppSettings.mode == TranscriptionMode.HOTSPOT_ONLY) {
                Log.e("NetworkWhisper", "Hotspot Only mode failed. All candidate URLs unreachable. Not falling back to HF.")
                return null
            }
        }
        
        // 2. Fallback to Hugging Face (Only runs if AUTO or CLOUD_ONLY)
        try {
            val hfBase = "https://alibaba2304-lectaaa.hf.space"
            Log.d("NetworkWhisper", "Attempting Hugging Face Lectaaa ZeroGPU Space")
            
            // STEP 1: Upload
            val uploadBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("files", "live_chunk.wav", wavBytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
                
            val uploadReq = Request.Builder().url("$hfBase/upload").post(uploadBody).build()
            val uploadRes = hfClient.newCall(uploadReq).execute()
            if (!uploadRes.isSuccessful) return null
            
            val uploadJson = org.json.JSONArray(uploadRes.body?.string() ?: "[]")
            if (uploadJson.length() == 0) return null
            val uploadedPath = uploadJson.getString(0)
            
            // STEP 2: Submit Job
            val predictBodyStr = "{\"data\": [{\"path\": \"$uploadedPath\"}]}"
            val predictBody = predictBodyStr.toRequestBody("application/json".toMediaType())
            val predictReq = Request.Builder().url("$hfBase/call/predict").post(predictBody).build()
            val predictRes = hfClient.newCall(predictReq).execute()
            
            if (!predictRes.isSuccessful) return null
            val predictResJson = JSONObject(predictRes.body?.string() ?: "{}")
            val eventId = predictResJson.optString("event_id", "")
            if (eventId.isEmpty()) return null
            
            // STEP 3: Read SSE Stream
            val sseClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val req = Request.Builder().url("$hfBase/call/predict/$eventId").get().build()
            var finalTranscript = ""
            
            sseClient.newCall(req).execute().use { response ->
                val inputStream = response.body?.byteStream() ?: return null
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        try {
                            val json = JSONObject(line!!.substring(6))
                            if (json.optString("msg") == "process_completed") {
                                val textArr = json.optJSONObject("output")?.optJSONArray("data")
                                finalTranscript = textArr?.optString(0, "") ?: ""
                                break
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
            
            if (finalTranscript.isNotBlank()) {
                Log.d("NetworkWhisper", "HF Transcription success: '$finalTranscript'")
                return WhisperResult(text = finalTranscript.trim(), avgLogProb = 0.0f)
            }
            return null
            
        } catch (e: Exception) {
            Log.e("NetworkWhisper", "Failed to contact Hugging Face Lectaaa API", e)
            return null
        }
    }

    private fun tryHotspotServer(pcmData: ShortArray): WhisperResult? {
        val wavBytes = createWavBytes(pcmData, 48000)
        return tryHotspotServer(wavBytes)
    }

    private fun tryHotspotServer(wavBytes: ByteArray): WhisperResult? {
        val hotspotIps = listOf("10.218.142.107", "192.168.137.1", "192.168.43.1") // Windows Hotspot and common Android gateway
        for (ip in hotspotIps) {
            try {
                Log.d("NetworkWhisper", "Attempting hotspot server at: $ip")
                val request = Request.Builder()
                    .url("http://$ip:8000/transcribe")
                    .addHeader("Content-Type", "audio/wav")
                    .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                    .build()
                    
                val response = localClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val jsonObject = JSONObject(responseBody)
                    val transcript = jsonObject.optString("text", "").trim()
                    Log.d("NetworkWhisper", "Hotspot PC Transcription success: '$transcript'")
                    return WhisperResult(text = transcript, avgLogProb = 0.0f)
                }
            } catch (e: Exception) {
                // Ignore and try next IP
            }
        }
        return null
    }

    private fun createWavBytes(pcmData: ShortArray, sampleRate: Int): ByteArray {
        val byteData = ByteArray(pcmData.size * 2)
        ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmData)

        val totalDataLen = byteData.size + 36
        val byteRate = sampleRate * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = 1 // 1 channel
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2
        header[33] = 0
        header[34] = 16 // 16 bit
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (byteData.size and 0xff).toByte()
        header[41] = (byteData.size shr 8 and 0xff).toByte()
        header[42] = (byteData.size shr 16 and 0xff).toByte()
        header[43] = (byteData.size shr 24 and 0xff).toByte()

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(byteData)
        return out.toByteArray()
    }
}
