package com.cortesnotetaker.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var title: String = "",
    var subject: String? = null,
    var audioFilePath: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var durationMs: Long = 0L,
    var language: String = "auto"
)