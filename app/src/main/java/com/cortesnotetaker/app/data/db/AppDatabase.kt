package com.cortesnotetaker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cortesnotetaker.app.data.db.dao.NoteDao
import com.cortesnotetaker.app.data.db.dao.SegmentDao
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import com.cortesnotetaker.app.data.db.entity.SegmentEntity

@Database(
    entities = [NoteEntity::class, SegmentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun segmentDao(): SegmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lecturepal_database"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}