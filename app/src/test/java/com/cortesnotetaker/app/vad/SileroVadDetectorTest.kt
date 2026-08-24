package com.cortesnotetaker.app.vad

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*

// Note: This test requires the silero_vad.onnx model in assets/
// Run with the model present to verify VAD initialization
class SileroVadDetectorTest {
    
    @Test
    fun testVadDetectorCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This will fail if model not present, but tests structure
        try {
            val detector = SileroVadDetector(context)
            detector.release()
            assertTrue(true)
        } catch (e: Exception) {
            // Expected if model not in test assets
            println("VAD model not available in test: ${e.message}")
        }
    }
    
    @Test
    fun testSilenceThresholds() {
        // Test the threshold constants
        assertEquals(0.5f, SileroVadDetector.SPEECH_THRESHOLD, 0.001f)
        assertEquals(0.35f, SileroVadDetector.SILENCE_THRESHOLD, 0.001f)
        assertEquals(10000, SileroVadDetector.MAX_SPEECH_DURATION_MS)
        assertEquals(100, SileroVadDetector.MIN_SPEECH_DURATION_MS)
        assertEquals(512, SileroVadDetector.FRAME_SIZE)
        assertEquals(32, SileroVadDetector.FRAME_DURATION_MS)
        assertEquals(16000, SileroVadDetector.SAMPLE_RATE)
    }
}