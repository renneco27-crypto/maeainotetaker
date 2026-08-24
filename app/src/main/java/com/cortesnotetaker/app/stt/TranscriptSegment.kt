package com.cortesnotetaker.app.stt

data class TranscriptSegment(
    val id: Long = 0L,
    val noteId: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val text: String = "",
    val isUnclear: Boolean = false,
    val confidenceScore: Float? = null,
    val speakerLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formatTimestamp(): String {
        val totalSeconds = startMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}