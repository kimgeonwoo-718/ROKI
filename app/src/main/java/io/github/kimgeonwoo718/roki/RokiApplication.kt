package io.github.kimgeonwoo718.roki

import android.app.Application

class RokiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
