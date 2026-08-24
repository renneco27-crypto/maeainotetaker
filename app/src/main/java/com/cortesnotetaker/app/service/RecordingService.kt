package com.cortesnotetaker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cortesnotetaker.app.MainActivity
import com.cortesnotetaker.app.R
import com.cortesnotetaker.app.audio.AudioCaptureManager
import com.cortesnotetaker.app.audio.MediaRecorderManager
import com.cortesnotetaker.app.stt.TranscriptSegment
import com.cortesnotetaker.app.stt.WhisperEngine
import com.cortesnotetaker.app.vad.SileroVadDetector
import com.cortesnotetaker.app.vad.SpeechSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

import android.content.pm.ServiceInfo
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class RecordingService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pipelineJob: Job? = null
    
    // Components
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var mediaRecorder: MediaRecorderManager
    private lateinit var vadDetector: SileroVadDetector
    private lateinit var whisperEngine: WhisperEngine
    
    // State
    private var currentNoteId: Long = 0
    private var currentAudioPath: String = ""
    private var recordingStartTime: Long = 0
    private var pausedTime: Long = 0
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeComponents()
    }

    private fun initializeComponents() {
        audioCapture = AudioCaptureManager(this)
        mediaRecorder = MediaRecorderManager(this)
        vadDetector = SileroVadDetector(this)
        whisperEngine = WhisperEngine()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> handleStartRecording(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStartRecording(intent: Intent?) {
        if (pipelineJob != null && pipelineJob?.isActive == true) return
        
        currentNoteId = intent?.getLongExtra(EXTRA_NOTE_ID, 0) ?: 0
        val subject = intent?.getStringExtra(EXTRA_SUBJECT) ?: ""
        
        currentAudioPath = AudioCaptureManager.getDefaultRecordingPath(this)
        
        // Start foreground immediately to satisfy Android background start requirements
        val notification = buildNotification("Starting recording...", subject)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        pipelineJob = serviceScope.launch {
            val whisperInitialized = whisperEngine.initialize(this@RecordingService)
            val audioStarted = audioCapture.start(currentAudioPath)
            
            if (!audioStarted || !whisperInitialized) {
                Log.e("RecordingService", "Failed to start recording components (audio=$audioStarted, whisper=$whisperInitialized)")
                stopSelf()
                return@launch
            }
            
            recordingStartTime = System.currentTimeMillis()
            pausedTime = 0
            isPaused = false
            
            updateNotification("Recording...", subject)
            
            val speechQueue = Channel<SpeechSegment>(Channel.UNLIMITED)

            // Worker 1: Real-time Audio Ingestion & VAD (non-blocking)
            launch(Dispatchers.Default) {
                for (pcmFrame in audioCapture.pcmFlow) {
                    if (!isPaused) {
                        val speechSegment = vadDetector.processPcmFrame(pcmFrame)
                        speechSegment?.let { segment ->
                            speechQueue.send(segment)
                        }
                    }
                }
            }

            // Worker 2: Asynchronous Whisper Transcriber
            launch(Dispatchers.Default) {
                for (segment in speechQueue) {
                    val startOffset = maxOf(0L, segment.startMs - recordingStartTime)
                    val endOffset = maxOf(startOffset, segment.endMs - recordingStartTime)
                    
                    val result = whisperEngine.transcribe(segment.pcmData, "auto")
                    result?.let { whisperResult ->
                        if (whisperResult.text.isNotBlank()) {
                            val transcriptSegment = TranscriptSegment(
                                noteId = currentNoteId,
                                startMs = startOffset,
                                endMs = endOffset,
                                text = whisperResult.text.trim(),
                                isUnclear = whisperResult.text == "[unclear]",
                                confidenceScore = whisperResult.avgLogProb,
                                timestamp = System.currentTimeMillis()
                            )
                            transcriptSharedFlow.emit(transcriptSegment)
                        }
                    }
                }
            }
        }
    }

    private fun handlePause() {
        if (isPaused) return
        isPaused = true
        pausedTime = System.currentTimeMillis()
        audioCapture.pause()
        updateNotification("Paused", "")
    }

    private fun handleResume() {
        if (!isPaused) return
        isPaused = false
        val pauseDuration = System.currentTimeMillis() - pausedTime
        recordingStartTime += pauseDuration
        audioCapture.resume()
        updateNotification("Recording...", "")
    }

    private fun handleStop() {
        pipelineJob?.cancel()
        audioCapture.stop()
        vadDetector.reset()
        whisperEngine.release()
        stopForeground(true)
        stopSelf()
    }

    fun getCurrentAudioPath(): String = currentAudioPath

    private fun buildNotification(title: String, subject: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_PAUSE }
        val resumeIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_RESUME }
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        
        val pausePending = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
        val resumePending = PendingIntent.getService(this, 2, resumeIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getService(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        val elapsedMs = if (isPaused) pausedTime - recordingStartTime else System.currentTimeMillis() - recordingStartTime
        val timeText = formatDuration(elapsedMs)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$subject  •  $timeText")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_pause, "Pause", pausePending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, subject: String) {
        val notification = buildNotification(title, subject)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active recording notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        serviceScope.cancel()
        audioCapture.stop()
        vadDetector.release()
        whisperEngine.release()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    companion object {
        val transcriptSharedFlow = MutableSharedFlow<TranscriptSegment>(replay = 0, extraBufferCapacity = 100)

        const val ACTION_START = "com.cortesnotetaker.app.START_RECORDING"
        const val ACTION_PAUSE = "com.cortesnotetaker.app.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.cortesnotetaker.app.RESUME_RECORDING"
        const val ACTION_STOP = "com.cortesnotetaker.app.STOP_RECORDING"
        
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_SUBJECT = "subject"
        
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
    }
}