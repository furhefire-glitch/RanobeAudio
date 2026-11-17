package com.ranobe.audioreader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.storage.CleanupWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RanobeAudioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        container.appScope.launch {
            container.sileroModelManager.refresh()
        }
        container.appScope.launch {
            androidx.work.WorkManager.getInstance(this@RanobeAudioApp).getWorkInfosByTagFlow("render").collect { work ->
                val ids = work.flatMap { it.tags }.filter { it.startsWith("chapter-") }
                    .mapNotNull { it.removePrefix("chapter-").toLongOrNull() }
                container.libraryRepository.reconcileRenderJobs(ids)
            }
        }
        container.appScope.launch {
            container.settingsRepository.flow.map { it.cleanupAfterHours > 0 }.distinctUntilChanged().collect {
                CleanupWorker.schedule(this@RanobeAudioApp, it)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val render = NotificationChannel(
            CHANNEL_RENDER,
            "Озвучка глав",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Прогресс фоновой озвучки" }
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK,
            "Воспроизведение",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Управление плеером" }
        manager.createNotificationChannels(listOf(render, playback))
    }

    companion object {
        const val CHANNEL_RENDER = "render"
        const val CHANNEL_PLAYBACK = "playback"
    }
}
