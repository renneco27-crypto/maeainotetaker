package com.cortesnotetaker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long): Int

    @Query("UPDATE notes SET title = :title, subject = :subject WHERE id = :id")
    suspend fun updateNote(id: Long, title: String, subject: String?): Int

    @Query("SELECT * FROM notes WHERE title LIKE :query OR subject LIKE :query ORDER BY createdAt DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>
}