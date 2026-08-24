package com.cortesnotetaker.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.service.RecordingService
import com.cortesnotetaker.app.stt.TranscriptSegment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(
    private val noteRepository: NoteRepository,
    private val segmentRepository: SegmentRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState

    private var recordingService: RecordingService? = null
    private var serviceConnected = false

    var elapsedTimer: java.util.Timer? = null

    data class RecordingUiState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val elapsedMs: Long = 0L,
        val subject: String = "",
        val segments: List<TranscriptSegment> = emptyList(),
        val error: String? = null
    )

    fun setService(service: RecordingService) {
        recordingService = service
        serviceConnected = true
        observeTranscripts()
        startElapsedTimer()
    }

    private fun observeTranscripts() {
        viewModelScope.launch {
            recordingService?.transcriptSharedFlow?.collect { segment ->
                // Save to database
                viewModelScope.launch {
                    segmentRepository.insert(
                        com.cortesnotetaker.app.data.db.entity.SegmentEntity(
                            noteId = segment.noteId,
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
                
                // Update UI state
                _uiState.update { current ->
                    current.copy(segments = current.segments + segment)
                }
            }
        }
    }

    fun startRecording(subject: String) {
        _uiState.update { it.copy(subject = subject, isRecording = true, error = null) }
        recordingService?.let { service ->
            val intent = android.content.Intent(service, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_SUBJECT, subject)
            }
            service.startService(intent)
        }
    }

    fun pauseRecording() {
        recordingService?.let { service ->
            val intent = android.content.Intent(service, RecordingService::class.java).apply {
                action = RecordingService.ACTION_PAUSE
            }
            service.startService(intent)
        }
        _uiState.update { it.copy(isPaused = true) }
        stopElapsedTimer()
    }

    fun resumeRecording() {
        recordingService?.let { service ->
            val intent = android.content.Intent(service, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RESUME
            }
            service.startService(intent)
        }
        _uiState.update { it.copy(isPaused = false) }
        startElapsedTimer()
    }

    fun stopRecording(onComplete: (Long) -> Unit) {
        stopElapsedTimer()
        _uiState.update { it.copy(isRecording = false, isPaused = false) }
        
        val currentState = _uiState.value
        val note = com.cortesnotetaker.app.data.db.entity.NoteEntity(
            title = currentState.subject.ifBlank { "Lecture ${formatTimestamp(System.currentTimeMillis())}" },
            subject = currentState.subject.ifBlank { null },
            audioFilePath = "", // Will be set by service
            durationMs = currentState.elapsedMs,
            language = "auto"
        )
        
        viewModelScope.launch {
            val noteId = noteRepository.insert(note)
            // Update segments with correct noteId
            currentState.segments.forEach { segment ->
                segmentRepository.insert(
                    com.cortesnotetaker.app.data.db.entity.SegmentEntity(
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
        elapsedTimer?.cancel()
        elapsedTimer = java.util.Timer().apply {
            scheduleAtFixedRate(java.util.TimerTask {
                _uiState.update { current ->
                    if (current.isRecording && !current.isPaused) {
                        current.copy(elapsedMs = current.elapsedMs + 1000)
                    } else {
                        current
                    }
                }
            }, 0, 1000)
        }
    }

    private fun stopElapsedTimer() {
        elapsedTimer?.cancel()
        elapsedTimer = null
    }

    override fun onCleared() {
        stopElapsedTimer()
        super.onCleared()
    }

    fun formatElapsedTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    fun formatTimestamp(timestamp: Long): String {
        val dateFormat = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(timestamp))
    }
}