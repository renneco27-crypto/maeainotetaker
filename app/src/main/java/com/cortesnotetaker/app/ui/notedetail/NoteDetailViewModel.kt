package com.cortesnotetaker.app.ui.notedetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.audio.AudioPlaybackManager
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NoteDetailViewModel(
    private val noteId: Long,
    private val noteRepository: NoteRepository,
    private val segmentRepository: SegmentRepository,
    private val context: Context
) : ViewModel() {

    class Factory(
        private val noteId: Long,
        private val noteRepository: NoteRepository,
        private val segmentRepository: SegmentRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NoteDetailViewModel(noteId, noteRepository, segmentRepository, context) as T
        }
    }

    data class NoteDetailUiState(
        val note: NoteEntity? = null,
        val segments: List<SegmentEntity> = emptyList(),
        val currentPlaybackPositionMs: Long = 0L,
        val activeSegmentIndex: Int = -1,
        val isPlaying: Boolean = false
    )

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    private val audioPlayback = AudioPlaybackManager(context)
    private var playbackPositionJob: Job? = null

    init {
        loadNote()
    }

    private fun loadNote() {
        viewModelScope.launch {
            val note = noteRepository.getNoteById(noteId)
            note?.let {
                _uiState.update { current -> current.copy(note = it) }
                audioPlayback.loadAudio(it.audioFilePath)
            }
            
            segmentRepository.getSegmentsForNote(noteId).collect { segments ->
                _uiState.update { current -> current.copy(segments = segments) }
            }
        }
    }

    fun playPause() {
        if (audioPlayback.isPlaying()) {
            audioPlayback.pause()
            _uiState.update { it.copy(isPlaying = false) }
            stopPositionUpdates()
        } else {
            audioPlayback.play()
            _uiState.update { it.copy(isPlaying = true) }
            startPositionUpdates()
        }
    }

    fun seekTo(positionMs: Long) {
        audioPlayback.seekTo(positionMs)
        _uiState.update { it.copy(currentPlaybackPositionMs = positionMs) }
        updateActiveSegment(positionMs)
    }

    fun onSegmentClick(segment: SegmentEntity) {
        seekTo(segment.startMs)
    }

    private fun startPositionUpdates() {
        playbackPositionJob?.cancel()
        playbackPositionJob = viewModelScope.launch {
            audioPlayback.positionFlow.collect { position ->
                _uiState.update { it.copy(currentPlaybackPositionMs = position) }
                updateActiveSegment(position)
            }
        }
    }

    private fun stopPositionUpdates() {
        playbackPositionJob?.cancel()
        playbackPositionJob = null
    }

    private fun updateActiveSegment(positionMs: Long) {
        val segments = _uiState.value.segments
        val activeIndex = segments.indexOfFirst { segment ->
            positionMs >= segment.startMs && positionMs <= segment.endMs
        }
        _uiState.update { it.copy(activeSegmentIndex = activeIndex) }
    }

    fun updateNoteTitle(newTitle: String) {
        _uiState.value.note?.let { note ->
            viewModelScope.launch {
                noteRepository.updateNote(note.id, newTitle, note.subject)
                _uiState.update { it.copy(note = it.note?.copy(title = newTitle)) }
            }
        }
    }

    fun updateSegmentTranscript(segmentId: Long, newTranscript: String) {
        viewModelScope.launch {
            segmentRepository.updateDisplayTranscript(segmentId, newTranscript)
            _uiState.update { current ->
                current.copy(segments = current.segments.map { segment ->
                    if (segment.id == segmentId) {
                        segment.copy(displayTranscript = newTranscript)
                    } else segment
                })
            }
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    override fun onCleared() {
        stopPositionUpdates()
        audioPlayback.release()
        super.onCleared()
    }
}