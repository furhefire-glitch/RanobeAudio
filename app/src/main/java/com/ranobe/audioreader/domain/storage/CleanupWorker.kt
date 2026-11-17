package com.ranobe.audioreader.domain.storage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ranobe.audioreader.RanobeAudioApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RanobeAudioApp).container
        return try {
            container.storageRepository.clearExpiredAudio(container.settingsRepository.current().cleanupAfterHours)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("CleanupWorker", "Не удалось очистить прослушанное аудио", error)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (enabled) manager.enqueueUniquePeriodicWork("listened-audio-cleanup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.HOURS).build())
            else manager.cancelUniqueWork("listened-audio-cleanup")
        }
    }
}
