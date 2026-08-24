package com.cortesnotetaker.app.stt

data class WhisperResult(
    var text: String = "",
    var avgLogProb: Float = 0f,
    var segments: List<WhisperSegment> = emptyList()
)

data class WhisperSegment(
    var text: String = "",
    var startMs: Long = 0L,
    var endMs: Long = 0L,
    var avgLogProb: Float = 0f
)