package com.cortesnotetaker.app

import android.app.Application
import android.util.Log
import com.cortesnotetaker.app.di.allModules
import com.cortesnotetaker.app.stt.WhisperEngine
import com.cortesnotetaker.app.vad.SileroVadDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        // Prewarm VAD model in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vad = SileroVadDetector(this@LecturePalApp)
                Log.d("LecturePalApp", "Pre-warmed VAD engine successfully")
            } catch (e: Exception) {
                Log.e("LecturePalApp", "Pre-warming error", e)
            }
        }
    }
}