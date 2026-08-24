package com.cortesnotetaker.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("startMs")]
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var noteId: Long = 0L,
    var startMs: Long = 0L,
    var endMs: Long = 0L,
    var rawTranscript: String = "",
    var displayTranscript: String = "",
    var isUnclear: Boolean = false,
    var confidenceScore: Float? = null,
    var speakerLabel: String? = null,
    var createdAt: Long = System.currentTimeMillis()
)