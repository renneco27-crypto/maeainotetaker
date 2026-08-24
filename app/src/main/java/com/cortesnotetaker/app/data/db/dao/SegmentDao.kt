package com.cortesnotetaker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: SegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<SegmentEntity>): List<Long>

    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs ASC")
    fun getSegmentsForNote(noteId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs ASC")
    suspend fun getSegmentsForNoteSync(noteId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun getSegmentById(id: Long): SegmentEntity?

    @Update
    suspend fun update(segment: SegmentEntity): Int

    @Query("UPDATE segments SET displayTranscript = :displayTranscript WHERE id = :id")
    suspend fun updateDisplayTranscript(id: Long, displayTranscript: String): Int

    @Query("DELETE FROM segments WHERE noteId = :noteId")
    suspend fun deleteSegmentsForNote(noteId: Long): Int

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegment(id: Long): Int
}