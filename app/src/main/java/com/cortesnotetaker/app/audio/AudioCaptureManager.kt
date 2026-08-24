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
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private var pcmChannel = Channel<ShortArray>(Channel.UNLIMITED)
    val pcmFlow: ReceiveChannel<ShortArray> get() = pcmChannel

    private var outputFilePath: String? = null
    private var wavOutputStream: FileOutputStream? = null
    private var totalPcmBytesWritten: Long = 0L

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 512 // 32ms at 16kHz
        const val FRAME_DURATION_MS = 32

        fun getDefaultRecordingPath(context: Context): String {
            val dir = File(context.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "recording_${System.currentTimeMillis()}.wav"
            return File(dir, fileName).absolutePath
        }
    }

    fun start(outputPath: String? = null): Boolean {
        if (isRecording.value) return true

        outputFilePath = outputPath ?: getDefaultRecordingPath(context)
        totalPcmBytesWritten = 0L

        try {
            val file = File(outputFilePath!!)
            file.parentFile?.mkdirs()
            wavOutputStream = FileOutputStream(file)
            wavOutputStream?.write(ByteArray(44)) // 44-byte WAV header placeholder
        } catch (e: Exception) {
            Log.e("AudioCapture", "Failed to create WAV file: ${e.message}", e)
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e("AudioCapture", "Permission denied for AudioRecord", e)
            closeWavFile()
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCapture", "AudioRecord initialization failed")
            release()
            closeWavFile()
            return false
        }

        if (pcmChannel.isClosedForSend) {
            pcmChannel = Channel(Channel.UNLIMITED)
        }

        audioRecord?.startRecording()
        isRecording.value = true

        Thread(startCaptureLoop(), "AudioCaptureThread").start()
        Log.d("AudioCapture", "AudioRecord started, saving to $outputFilePath")
        return true
    }

    private fun startCaptureLoop(): Runnable {
        return Runnable {
            val buffer = ShortArray(FRAME_SIZE)
            val byteBuffer = ByteBuffer.allocate(FRAME_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
            var frameCount = 0L
            while (isRecording.value) {
                val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: 0
                if (read > 0) {
                    frameCount++
                    val frame = buffer.copyOf(read)
                    pcmChannel.trySend(frame)

                    try {
                        byteBuffer.clear()
                        for (i in 0 until read) {
                            byteBuffer.putShort(buffer[i])
                        }
                        val bytesToWrite = read * 2
                        wavOutputStream?.write(byteBuffer.array(), 0, bytesToWrite)
                        totalPcmBytesWritten += bytesToWrite
                    } catch (e: Exception) {
                        Log.e("AudioCapture", "Error writing audio to WAV file", e)
                    }

                    if (frameCount % 100L == 0L) {
                        Log.d("AudioCapture", "AudioCapture running: frame #$frameCount, bytes=$totalPcmBytesWritten")
                    }
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
        Thread(startCaptureLoop(), "AudioCaptureThread").start()
        Log.d("AudioCapture", "AudioRecord resumed")
        return true
    }

    fun stop(): String? {
        isRecording.value = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping AudioRecord", e)
        }
        release()
        pcmChannel.close()
        closeWavFile()
        Log.d("AudioCapture", "AudioRecord stopped: $outputFilePath")
        return outputFilePath
    }

    private fun closeWavFile() {
        try {
            wavOutputStream?.flush()
            wavOutputStream?.close()
            wavOutputStream = null

            outputFilePath?.let { path ->
                val file = File(path)
                if (file.exists() && totalPcmBytesWritten > 0) {
                    writeWavHeader(file, totalPcmBytesWritten, SAMPLE_RATE, 1, 16)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error closing WAV file", e)
        }
    }

    private fun writeWavHeader(
        file: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
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
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun release() {
        audioRecord?.release()
        audioRecord = null
    }

    fun getCurrentFilePath(): String? = outputFilePath
    fun getCurrentLevel(): Float = 0f
}