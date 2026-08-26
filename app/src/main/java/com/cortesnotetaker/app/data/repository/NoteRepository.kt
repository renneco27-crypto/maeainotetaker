package com.cortesnotetaker.app.data.repository

import com.cortesnotetaker.app.data.db.dao.NoteDao
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {
    suspend fun insert(note: NoteEntity): Long = noteDao.insert(note)

    fun getAllNotes(): Flow<List<NoteEntity>> = noteDao.getAllNotes()

    suspend fun getNoteById(id: Long): NoteEntity? = noteDao.getNoteById(id)

    suspend fun deleteNote(id: Long): Int = noteDao.deleteNote(id)

    suspend fun updateNote(id: Long, title: String, subject: String?): Int = noteDao.updateNote(id, title, subject)
    
    suspend fun updateStatus(id: Long, status: String, isUnread: Boolean): Int = noteDao.updateStatus(id, status, isUnread)

    suspend fun updateProgress(id: Long, progress: Int): Int = noteDao.updateProgress(id, progress)

    suspend fun updateJobId(id: Long, jobId: String): Int = noteDao.updateJobId(id, jobId)

    suspend fun markAsRead(id: Long): Int = noteDao.markAsRead(id)

    suspend fun getProcessingNotes(): List<NoteEntity> = noteDao.getProcessingNotes()

    fun searchNotes(query: String): Flow<List<NoteEntity>> = noteDao.searchNotes("%$query%")
}