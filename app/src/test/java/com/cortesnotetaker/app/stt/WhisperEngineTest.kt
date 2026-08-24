package com.cortesnotetaker.app.stt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

// Note: This test requires the whisper.cpp model and native library
// Run with proper setup to verify Whisper integration
class WhisperEngineTest {
    
    @Test
    fun testWhisperEngineCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = WhisperEngine()
        // Just test instantiation - actual initialization requires model
        assertNotNull(engine)
    }
    
    @Test
    fun testTranscriptSegmentFormatting() {
        val segment = TranscriptSegment(
            startMs = 3661000, // 1:01:01
            text = "Test transcript",
            isUnclear = false
        )
        
        assertEquals("1:01:01", segment.formatTimestamp())
        
        val shortSegment = TranscriptSegment(
            startMs = 61000, // 1:01
            text = "Short",
            isUnclear = false
        )
        
        assertEquals("01:01", shortSegment.formatTimestamp())
    }
    
    @Test
    fun testUnclearSegment() {
        val unclearSegment = TranscriptSegment(
            startMs = 0,
            text = "[unclear]",
            isUnclear = true
        )
        
        assertTrue(unclearSegment.isUnclear)
        assertEquals("[unclear]", unclearSegment.text)
    }
}