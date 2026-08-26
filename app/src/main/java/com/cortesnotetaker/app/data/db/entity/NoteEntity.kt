package com.cortesnotetaker.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var title: String = "",
    var subject: String? = null,
    var audioFilePath: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var durationMs: Long = 0L,
    var language: String = "auto",
    var jobId: String? = null,
    var status: String = "completed", // "processing", "completed", "error"
    var progress: Int = 0,
    var isUnread: Boolean = false
)