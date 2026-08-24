package com.cortesnotetaker.app.data.repository

import com.cortesnotetaker.app.data.db.dao.SegmentDao
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import kotlinx.coroutines.flow.Flow

class SegmentRepository(private val segmentDao: SegmentDao) {
    suspend fun insert(segment: SegmentEntity): Long = segmentDao.insert(segment)

    suspend fun insertAll(segments: List<SegmentEntity>): List<Long> = segmentDao.insertAll(segments)

    fun getSegmentsForNote(noteId: Long): Flow<List<SegmentEntity>> = segmentDao.getSegmentsForNote(noteId)

    suspend fun getSegmentsForNoteSync(noteId: Long): List<SegmentEntity> = segmentDao.getSegmentsForNoteSync(noteId)

    suspend fun updateDisplayTranscript(id: Long, displayTranscript: String): Int =
        segmentDao.updateDisplayTranscript(id, displayTranscript)

    suspend fun update(segment: SegmentEntity): Int = segmentDao.update(segment)

    suspend fun deleteSegmentsForNote(noteId: Long): Int = segmentDao.deleteSegmentsForNote(noteId)

    suspend fun deleteSegment(id: Long): Int = segmentDao.deleteSegment(id)
}