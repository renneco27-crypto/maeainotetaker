package com.cortesnotetaker.app.ui.notelist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.stt.FileUploaderClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NoteListViewModel(
    private val noteRepository: NoteRepository,
    private val segmentRepository: SegmentRepository,
    private val fileUploaderClient: FileUploaderClient
) : ViewModel() {
    private val _notes = MutableStateFlow<List<com.cortesnotetaker.app.data.db.entity.NoteEntity>>(emptyList())
    val notes: StateFlow<List<com.cortesnotetaker.app.data.db.entity.NoteEntity>> = _notes

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress = _importProgress.asStateFlow()

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            noteRepository.getAllNotes().collect { notes ->
                _notes.value = notes
            }
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            if (query.isBlank()) {
                noteRepository.getAllNotes().collect { notes ->
                    _notes.value = notes
                }
            } else {
                noteRepository.searchNotes(query).collect { notes ->
                    _notes.value = notes
                }
            }
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            noteRepository.deleteNote(id)
            loadNotes()
        }
    }
    
    fun handleSharedAudioFile(uri: Uri) {
        viewModelScope.launch {
            _importProgress.value = "Preparing file..."
            val transcript = fileUploaderClient.uploadAndTranscribe(uri) { progress ->
                _importProgress.value = progress
            }
            
            if (!transcript.isNullOrBlank()) {
                val newNote = com.cortesnotetaker.app.data.db.entity.NoteEntity(
                    title = "Imported Audio",
                    createdAt = System.currentTimeMillis()
                )
                val noteId = noteRepository.insert(newNote)
                
                val segment = com.cortesnotetaker.app.data.db.entity.SegmentEntity(
                    noteId = noteId,
                    startTimeMs = 0L,
                    endTimeMs = 0L,
                    rawTranscript = transcript,
                    displayTranscript = transcript,
                    isSilent = false
                )
                segmentRepository.insert(segment)
                
                loadNotes()
            }
            _importProgress.value = null
        }
    }
}