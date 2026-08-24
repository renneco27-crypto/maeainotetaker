package com.cortesnotetaker.app.di

import android.content.Context
import com.cortesnotetaker.app.data.db.AppDatabase
import com.cortesnotetaker.app.data.db.dao.NoteDao
import com.cortesnotetaker.app.data.db.dao.SegmentDao
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.audio.AudioCaptureManager
import com.cortesnotetaker.app.audio.AudioPlaybackManager
import com.cortesnotetaker.app.audio.MediaRecorderManager
import com.cortesnotetaker.app.stt.WhisperEngine
import com.cortesnotetaker.app.vad.SileroVadDetector
import com.cortesnotetaker.app.ui.notelist.NoteListViewModel
import com.cortesnotetaker.app.ui.recording.RecordingViewModel
import com.cortesnotetaker.app.ui.notedetail.NoteDetailViewModel
import org.koin.android.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

val appModule = module {
    single<AppDatabase> { AppDatabase.getInstance(androidContext()) }
    single<NoteDao> { get<AppDatabase>().noteDao() }
    single<SegmentDao> { get<AppDatabase>().segmentDao() }
}

val repositoryModule = module {
    single { NoteRepository(get()) }
    single { SegmentRepository(get()) }
}

val audioModule = module {
    factory { AudioCaptureManager(androidContext()) }
    factory { MediaRecorderManager(androidContext()) }
    single { SileroVadDetector(androidContext()) }
    single { WhisperEngine() }
    factory { AudioPlaybackManager(androidContext()) }
}

val viewModelModule = module {
    viewModel { NoteListViewModel(get()) }
    viewModel { RecordingViewModel(get(), get()) }
    viewModel { (noteId: Long) -> NoteDetailViewModel(noteId, get(), get(), androidContext()) }
}

val allModules = listOf(appModule, repositoryModule, audioModule, viewModelModule)

class LecturePalApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@LecturePalApp)
            modules(allModules)
        }
    }
}