package com.cortesnotetaker.app

import android.app.Application
import com.cortesnotetaker.app.di.allModules
import com.cortesnotetaker.app.di.startKoin
import org.koin.android.logger.androidLogger
import org.koin.core.context.startKoin

class LecturePalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@LecturePalApp)
            modules(allModules)
        }
    }
}