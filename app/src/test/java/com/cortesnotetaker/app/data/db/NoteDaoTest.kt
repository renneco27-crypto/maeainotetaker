package com.cortesnotetaker.app.data.db

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class NoteDaoTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AppDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var segmentDao: SegmentDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = database.noteDao()
        segmentDao = database.segmentDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndRetrieveNote() = runBlocking {
        val note = NoteEntity(
            title = "Test Lecture",
            subject = "Mathematics",
            audioFilePath = "/path/to/audio.m4a",
            durationMs = 3600000,
            language = "en"
        )
        
        val id = noteDao.insert(note)
        assertTrue(id > 0)
        
        val retrieved = noteDao.getNoteById(id)
        assertNotNull(retrieved)
        assertEquals("Test Lecture", retrieved?.title)
        assertEquals("Mathematics", retrieved?.subject)
    }

    @Test
    fun insertAndRetrieveSegments() = runBlocking {
        val note = NoteEntity(
            title = "Test Lecture",
            subject = "Mathematics",
            audioFilePath = "/path/to/audio.m4a",
            durationMs = 3600000
        )
        val noteId = noteDao.insert(note)
        
        val segment = SegmentEntity(
            noteId = noteId,
            startMs = 0,
            endMs = 5000,
            rawTranscript = "Hello world",
            displayTranscript = "Hello world",
            isUnclear = false,
            confidenceScore = -0.3f
        )
        
        val segmentId = segmentDao.insert(segment)
        assertTrue(segmentId > 0)
        
        val segments = segmentDao.getSegmentsForNoteSync(noteId)
        assertEquals(1, segments.size)
        assertEquals("Hello world", segments[0].rawTranscript)
        assertEquals("Hello world", segments[0].displayTranscript)
        assertFalse(segments[0].isUnclear)
    }

    @Test
    fun updateSegmentDisplayTranscript() = runBlocking {
        val note = NoteEntity(title = "Test", audioFilePath = "/path", durationMs = 1000)
        val noteId = noteDao.insert(note)
        
        val segment = SegmentEntity(
            noteId = noteId,
            startMs = 0,
            endMs = 5000,
            rawTranscript = "Original",
            displayTranscript = "Original",
            isUnclear = false
        )
        val segmentId = segmentDao.insert(segment)
        
        segmentDao.updateDisplayTranscript(segmentId, "Updated transcript")
        
        val updated = segmentDao.getSegmentById(segmentId)
        assertEquals("Updated transcript", updated?.displayTranscript)
        assertEquals("Original", updated?.rawTranscript) // raw should not change
    }

    @Test
    fun deleteNoteCascadesToSegments() = runBlocking {
        val note = NoteEntity(title = "Test", audioFilePath = "/path", durationMs = 1000)
        val noteId = noteDao.insert(note)
        
        val segment = SegmentEntity(
            noteId = noteId,
            startMs = 0,
            endMs = 5000,
            rawTranscript = "Test",
            displayTranscript = "Test",
            isUnclear = false
        )
        segmentDao.insert(segment)
        
        noteDao.deleteNote(noteId)
        
        val remainingSegments = segmentDao.getSegmentsForNoteSync(noteId)
        assertTrue(remainingSegments.isEmpty())
    }
}