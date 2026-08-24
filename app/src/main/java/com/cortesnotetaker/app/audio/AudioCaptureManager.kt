package com.cortesnotetaker.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioCaptureManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(FRAME_SIZE * 2)
    }

    private val isRecording = MutableStateFlow(false)
    val isRecordingFlow = isRecording.asStateFlow()

    private val pcmChannel = Channel<ShortArray>(Channel.UNLIMITED)
    val pcmFlow: ReceiveChannel<ShortArray> = pcmChannel

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 512 // 32ms at 16kHz
        const val FRAME_DURATION_MS = 32
    }

    fun start(): Boolean {
        if (isRecording.value) return true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCapture", "AudioRecord initialization failed")
            release()
            return false
        }

        audioRecord?.startRecording()
        isRecording.value = true

        // Start capture loop on IO dispatcher
        Thread(startCaptureLoop()).start()

        Log.d("AudioCapture", "AudioRecord started")
        return true
    }

    private fun startCaptureLoop(): Runnable {
        return Runnable {
            val buffer = ShortArray(FRAME_SIZE)
            while (isRecording.value) {
                val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: 0
                if (read > 0) {
                    val frame = buffer.copyOf(read)
                    pcmChannel.trySend(frame)
                } else if (read < 0) {
                    Log.e("AudioCapture", "AudioRecord read error: $read")
                }
            }
        }
    }

    fun pause() {
        audioRecord?.stop()
        isRecording.value = false
        Log.d("AudioCapture", "AudioRecord paused")
    }

    fun resume(): Boolean {
        if (isRecording.value) return true
        audioRecord?.startRecording()
        isRecording.value = true
        Thread(startCaptureLoop()).start()
        Log.d("AudioCapture", "AudioRecord resumed")
        return true
    }

    fun stop() {
        isRecording.value = false
        audioRecord?.stop()
        release()
        pcmChannel.close()
        Log.d("AudioCapture", "AudioRecord stopped")
    }

    private fun release() {
        audioRecord?.release()
        audioRecord = null
    }

    fun getCurrentLevel(): Float {
        // Not implemented - would need RMS calculation
        return 0f
    }
}