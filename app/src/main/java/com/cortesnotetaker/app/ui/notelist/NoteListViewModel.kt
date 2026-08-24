package com.cortesnotetaker.app.ui.notelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortesnotetaker.app.data.repository.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NoteListViewModel(private val noteRepository: NoteRepository) : ViewModel() {
    private val _notes = MutableStateFlow<List<com.cortesnotetaker.app.data.db.entity.NoteEntity>>(emptyList())
    val notes: StateFlow<List<com.cortesnotetaker.app.data.db.entity.NoteEntity>> = _notes

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

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

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            noteRepository.deleteNote(noteId)
        }
    }
}