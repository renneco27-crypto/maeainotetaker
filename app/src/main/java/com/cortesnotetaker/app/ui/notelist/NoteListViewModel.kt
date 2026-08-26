package com.cortesnotetaker.app.ui.notelist

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.stt.FileUploaderClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NoteListViewModel(
    private val noteRepository: NoteRepository,
    private val segmentRepository: SegmentRepository,
    private val fileUploaderClient: FileUploaderClient
) : ViewModel() {
    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress = _importProgress.asStateFlow()

    init {
        loadNotes()
        resumeProcessingNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            noteRepository.getAllNotes().collect { notesList ->
                _notes.value = notesList
            }
        }
    }

    private fun resumeProcessingNotes() {
        viewModelScope.launch {
            val processingNotes = noteRepository.getProcessingNotes()
            for (note in processingNotes) {
                note.jobId?.let { jobId ->
                    launch { pollJobUntilDone(note.id, jobId) }
                }
            }
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            if (query.isBlank()) {
                noteRepository.getAllNotes().collect { notesList ->
                    _notes.value = notesList
                }
            } else {
                noteRepository.searchNotes(query).collect { notesList ->
                    _notes.value = notesList
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

    fun markNoteAsRead(id: Long) {
        viewModelScope.launch {
            noteRepository.markAsRead(id)
        }
    }
    
    fun handleSharedAudioFile(uri: Uri) {
        viewModelScope.launch {
            // 1. Immediately create the note in "processing" state so it appears in the list right away
            val newNote = NoteEntity(
                title = "Imported Lecture",
                createdAt = System.currentTimeMillis(),
                status = "processing",
                isUnread = false
            )
            val noteId = noteRepository.insert(newNote)
            Log.d("NoteListViewModel", "Created processing note with ID: $noteId")

            // 2. Upload file to PC in background
            _importProgress.value = "Uploading to PC..."
            val jobId = fileUploaderClient.uploadAudioFile(uri)
            _importProgress.value = null

            if (jobId != null) {
                Log.d("NoteListViewModel", "Note $noteId assigned Job ID: $jobId")
                noteRepository.updateJobId(noteId, jobId)
                // 3. Poll in background until finished
                launch { pollJobUntilDone(noteId, jobId) }
            } else {
                Log.e("NoteListViewModel", "Upload failed for note $noteId")
                noteRepository.updateStatus(noteId, "error", false)
            }
        }
    }

    private suspend fun pollJobUntilDone(noteId: Long, jobId: String) {
        Log.d("NoteListViewModel", "Starting polling for note $noteId, jobId: $jobId")
        while (true) {
            delay(3000)
            val result = fileUploaderClient.checkJobStatus(jobId)
            if (result != null) {
                when (result.status) {
                    "completed" -> {
                        val transcript = result.text ?: ""
                        Log.d("NoteListViewModel", "Job $jobId completed. Saving segments for note $noteId")
                        if (transcript.isNotBlank()) {
                            val segment = SegmentEntity(
                                noteId = noteId,
                                startMs = 0L,
                                endMs = 0L,
                                rawTranscript = transcript,
                                displayTranscript = transcript
                            )
                            segmentRepository.insert(segment)
                        }
                        // Mark as completed and isUnread = true (Green indicator!)
                        noteRepository.updateStatus(noteId, "completed", isUnread = true)
                        break
                    }
                    "error" -> {
                        Log.e("NoteListViewModel", "Job $jobId returned error")
                        noteRepository.updateStatus(noteId, "error", isUnread = false)
                        break
                    }
                    "processing" -> {
                        if (result.progress > 0) {
                            noteRepository.updateProgress(noteId, result.progress)
                        }
                        Log.d("NoteListViewModel", "Job $jobId is processing (${result.progress}%)...")
                    }
                }
            }
        }
    }
}