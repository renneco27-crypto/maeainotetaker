package com.cortesnotetaker.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.IOException

class MediaRecorderManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputPath: String? = null
    private var isRecording = false
    private var isPaused = false

    fun start(outputPath: String): Boolean {
        if (isRecording) return true

        currentOutputPath = outputPath
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputPath)
        }

        return try {
            mediaRecorder?.prepare()
            mediaRecorder?.start()
            isRecording = true
            isPaused = false
            Log.d("MediaRecorder", "Recording started: $outputPath")
            true
        } catch (e: IOException) {
            Log.e("MediaRecorder", "Prepare failed", e)
            release()
            false
        } catch (e: IllegalStateException) {
            Log.e("MediaRecorder", "Start failed", e)
            release()
            false
        }
    }

    fun pause() {
        if (isRecording && !isPaused && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            mediaRecorder?.pause()
            isPaused = true
            Log.d("MediaRecorder", "Recording paused")
        }
    }

    fun resume() {
        if (isRecording && isPaused && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
            isPaused = false
            Log.d("MediaRecorder", "Recording resumed")
        }
    }

    fun stop(): String? {
        if (!isRecording) return currentOutputPath

        try {
            mediaRecorder?.stop()
        } catch (e: IllegalStateException) {
            Log.e("MediaRecorder", "Stop failed", e)
        }
        release()
        isRecording = false
        isPaused = false
        Log.d("MediaRecorder", "Recording stopped: $currentOutputPath")
        return currentOutputPath
    }

    fun getCurrentOutputPath(): String? = currentOutputPath

    fun isCurrentlyRecording(): Boolean = isRecording

    fun isCurrentlyPaused(): Boolean = isPaused

    private fun release() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
    }

    companion object {
        fun getDefaultRecordingPath(context: Context): String {
            val dir = File(context.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "recording_${System.currentTimeMillis()}.m4a"
            return File(dir, fileName).absolutePath
        }
    }
}