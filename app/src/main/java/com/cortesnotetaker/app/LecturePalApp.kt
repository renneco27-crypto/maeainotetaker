package com.cortesnotetaker.app

import android.app.Application
import com.cortesnotetaker.app.di.allModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class LecturePalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@LecturePalApp)
            modules(allModules)
        }

        // Prewarm Whisper and VAD models in background
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val whisper = com.cortesnotetaker.app.stt.WhisperEngine()
                whisper.initialize(this@LecturePalApp)
                val vad = com.cortesnotetaker.app.vad.SileroVadDetector(this@LecturePalApp)
                android.util.Log.d("LecturePalApp", "Pre-warmed Whisper & VAD engines successfully")
            } catch (e: Exception) {
                android.util.Log.e("LecturePalApp", "Pre-warming error", e)
            }
        }
    }
}