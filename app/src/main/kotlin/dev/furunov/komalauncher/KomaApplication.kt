package dev.furunov.komalauncher

import android.app.Application
import android.content.ComponentCallbacks2

class KomaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // ホームが裏に回ったら、アイコンのキャッシュを捨てる。次に必要になったら読み直す
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) container.iconLoader.trim()
    }
}
