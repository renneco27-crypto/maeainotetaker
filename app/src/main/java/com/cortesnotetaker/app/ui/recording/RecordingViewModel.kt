package com.cortesnotetaker.app.ui.recording

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.service.RecordingService
import com.cortesnotetaker.app.stt.TranscriptSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingViewModel(
    private val noteRepository: NoteRepository,
    private val segmentRepository: SegmentRepository
) : ViewModel() {

    data class RecordingUiState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val elapsedTimeMs: Long = 0L,
        val subject: String = "",
        val liveTranscriptSegments: List<TranscriptSegment> = emptyList(),
        val error: String? = null
    )

    val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var recordingService: RecordingService? = null
    private var timerJob: Job? = null
    private var transcriptCollectionJob: Job? = null

    init {
        observeTranscripts()
    }

    fun setService(service: RecordingService) {
        recordingService = service
    }

    private fun observeTranscripts() {
        transcriptCollectionJob?.cancel()
        transcriptCollectionJob = viewModelScope.launch {
            RecordingService.transcriptSharedFlow.collect { segment ->
                _uiState.update { current ->
                    val exists = current.liveTranscriptSegments.any {
                        it.startMs == segment.startMs && it.text == segment.text
                    }
                    if (!exists) {
                        current.copy(liveTranscriptSegments = current.liveTranscriptSegments + segment)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun startRecording(context: android.content.Context, subject: String) {
        _uiState.update { it.copy(subject = subject, isRecording = true, isPaused = false, error = null, liveTranscriptSegments = emptyList(), elapsedTimeMs = 0L) }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_SUBJECT, subject)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        startElapsedTimer()
    }

    fun pauseRecording(context: android.content.Context) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PAUSE
        }
        context.startService(intent)
        _uiState.update { it.copy(isPaused = true) }
        stopElapsedTimer()
    }

    fun resumeRecording(context: android.content.Context) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_RESUME
        }
        context.startService(intent)
        _uiState.update { it.copy(isPaused = false) }
        startElapsedTimer()
    }

    fun stopRecording(context: android.content.Context, onComplete: (Long) -> Unit) {
        stopElapsedTimer()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)

        val currentState = _uiState.value
        val audioPath = recordingService?.getCurrentAudioPath() ?: ""
        _uiState.update { it.copy(isRecording = false, isPaused = false) }

        val note = NoteEntity(
            title = currentState.subject.ifBlank { "Lecture ${formatTimestamp(System.currentTimeMillis())}" },
            subject = currentState.subject.ifBlank { null },
            audioFilePath = audioPath,
            durationMs = currentState.elapsedTimeMs,
            language = "auto"
        )

        viewModelScope.launch {
            val noteId = noteRepository.insert(note)
            currentState.liveTranscriptSegments.forEach { segment ->
                segmentRepository.insert(
                    SegmentEntity(
                        noteId = noteId,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        rawTranscript = segment.text,
                        displayTranscript = segment.text,
                        isUnclear = segment.isUnclear,
                        confidenceScore = segment.confidenceScore,
                        speakerLabel = segment.speakerLabel,
                        createdAt = segment.timestamp
                    )
                )
            }
            onComplete(noteId)
        }
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_uiState.value.isRecording && !_uiState.value.isPaused) {
                    _uiState.update { it.copy(elapsedTimeMs = it.elapsedTimeMs + 1000) }
                }
            }
        }
    }

    private fun stopElapsedTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onCleared() {
        stopElapsedTimer()
        transcriptCollectionJob?.cancel()
        super.onCleared()
    }

    private fun formatTimestamp(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
}