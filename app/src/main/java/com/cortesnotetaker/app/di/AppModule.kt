package com.cortesnotetaker.app.di

import com.cortesnotetaker.app.audio.AudioCaptureManager
import com.cortesnotetaker.app.audio.AudioPlaybackManager
import com.cortesnotetaker.app.audio.MediaRecorderManager
import com.cortesnotetaker.app.data.db.AppDatabase
import com.cortesnotetaker.app.data.db.dao.NoteDao
import com.cortesnotetaker.app.data.db.dao.SegmentDao
import com.cortesnotetaker.app.data.repository.NoteRepository
import com.cortesnotetaker.app.data.repository.SegmentRepository
import com.cortesnotetaker.app.stt.WhisperEngine
import com.cortesnotetaker.app.ui.notedetail.NoteDetailViewModel
import com.cortesnotetaker.app.ui.notelist.NoteListViewModel
import com.cortesnotetaker.app.ui.recording.RecordingViewModel
import com.cortesnotetaker.app.vad.SileroVadDetector
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
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
    single { com.cortesnotetaker.app.stt.FileUploaderClient(androidContext()) }
    factory { AudioPlaybackManager(androidContext()) }
}

val viewModelModule = module {
    viewModel { NoteListViewModel(get(), get(), get()) }
    viewModel { RecordingViewModel(get(), get()) }
    viewModel { (noteId: Long) -> NoteDetailViewModel(noteId, get(), get(), androidContext()) }
}

val allModules = listOf(appModule, repositoryModule, audioModule, viewModelModule)