package com.ranobe.audioreader.domain.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ranobe.audioreader.RanobeAudioApp
import com.ranobe.audioreader.util.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val modelId = inputData.getString(KEY_MODEL) ?: throw IllegalArgumentException("Не указан пак")
        ModelPackArchive.validateId(modelId)
        setForeground(foreground(modelId, 0, -1))
        coroutineScope {
            val updates = Channel<Pair<Long, Long>>(Channel.CONFLATED)
            val reporter = launch {
                var last = 0L
                var previousTotal = Long.MIN_VALUE
                for ((done, total) in updates) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - last < 300 && done != total && total == previousTotal) continue
                    setProgress(Data.Builder().putLong(KEY_DONE, done).putLong(KEY_TOTAL, total).build())
                    setForeground(foreground(modelId, done, total))
                    previousTotal = total
                    last = now
                }
            }
            try {
                val container = (applicationContext as RanobeAudioApp).container
                container.sileroModelManager.download(modelId) { done, total -> updates.trySend(done to total) }
            } finally {
                updates.close()
            }
            reporter.join()
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(Data.Builder().putString(KEY_ERROR, e.message?.take(1000) ?: "Не удалось скачать пак").build())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(inputData.getString(KEY_MODEL).orEmpty(), 0, -1)

    private fun foreground(modelId: String, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (done.coerceAtMost(total) * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Пак $modelId")
            .setContentText(if (total > 0) "Скачивание: $percent%" else "Загрузка и проверка пака…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    companion object {
        const val TAG = "models"
        const val KEY_MODEL = "modelId"
        const val KEY_DONE = "doneBytes"
        const val KEY_TOTAL = "totalBytes"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_downloads"

        fun uniqueName(id: String): String {
            ModelPackArchive.validateId(id)
            return "model-$id"
        }

        suspend fun enqueue(context: Context, id: String) {
            val app = context.applicationContext as RanobeAudioApp
            val name = uniqueName(id)
            require(app.container.sileroModelManager.catalog.any { it.id == id }) { "Неизвестная модель" }
            val settings = app.container.settingsRepository.current()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL, id).build())
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build())
                .addTag(TAG)
                .addTag(name)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.awaitResult()
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(id))
        }
    }
}
