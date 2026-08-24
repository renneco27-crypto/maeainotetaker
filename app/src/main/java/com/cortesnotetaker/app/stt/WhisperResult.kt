package com.cortesnotetaker.app.stt

import kotlinx.serialization.Serializable

@Serializable
data class WhisperResult(
    var text: String = "",
    var avgLogProb: Float = 0f,
    var segments: List<WhisperSegment> = emptyList()
)

@Serializable
data class WhisperSegment(
    var text: String = "",
    var startMs: Long = 0L,
    var endMs: Long = 0L,
    var avgLogProb: Float = 0f
)