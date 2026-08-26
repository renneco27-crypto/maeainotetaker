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
    private val fileUploaderClient: FileUploaderClient,
    private val context: android.content.Context
) : ViewModel() {
    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress = _importProgress.asStateFlow()

    private val activePollingJobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

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
                    val job = launch { pollJobUntilDone(note.id, jobId) }
                    activePollingJobs[note.id] = job
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
            // 1. Cancel local polling job
            activePollingJobs[id]?.cancel()
            activePollingJobs.remove(id)

            // 2. Notify PC server to immediately stop Whisper inference if still processing
            val note = noteRepository.getNoteById(id)
            note?.jobId?.let { jobId ->
                if (note.status == "processing") {
                    Log.d("NoteListViewModel", "Aborting ongoing PC transcription for Job ID: $jobId")
                    fileUploaderClient.cancelJob(jobId)
                }
            }

            // 3. Delete from database
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
            // 1. Query original file name
            var displayName = "Imported Lecture"
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("NoteListViewModel", "Could not query display name: ${e.message}")
            }

            // 2. Copy audio stream to local app files directory so it can be played back offline
            val ext = displayName.substringAfterLast('.', "media")
            val localFile = java.io.File(context.filesDir, "imported_${System.currentTimeMillis()}.$ext")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("NoteListViewModel", "Saved imported audio locally to: ${localFile.absolutePath} (${localFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e("NoteListViewModel", "Failed to save imported audio locally: ${e.message}")
            }

            // 3. Extract exact duration
            var durationMs = 0L
            try {
                if (localFile.exists() && localFile.length() > 0) {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(localFile.absolutePath)
                    val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durationStr?.toLongOrNull() ?: 0L
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.w("NoteListViewModel", "Could not extract duration: ${e.message}")
            }

            // 4. Create the note in "processing" state with full audio path and duration
            val newNote = NoteEntity(
                title = displayName.substringBeforeLast('.').ifBlank { "Imported Lecture" },
                audioFilePath = localFile.absolutePath,
                durationMs = durationMs,
                createdAt = System.currentTimeMillis(),
                status = "processing",
                isUnread = false
            )
            val noteId = noteRepository.insert(newNote)
            Log.d("NoteListViewModel", "Created processing note with ID: $noteId, audio: ${localFile.absolutePath}, duration: ${durationMs}ms")

            // 5. Upload file to PC in background
            _importProgress.value = "Uploading to PC..."
            val jobId = fileUploaderClient.uploadAudioFile(uri)
            _importProgress.value = null

            if (jobId != null) {
                Log.d("NoteListViewModel", "Note $noteId assigned Job ID: $jobId")
                noteRepository.updateJobId(noteId, jobId)
                // 6. Poll in background until finished
                val job = launch { pollJobUntilDone(noteId, jobId) }
                activePollingJobs[noteId] = job
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