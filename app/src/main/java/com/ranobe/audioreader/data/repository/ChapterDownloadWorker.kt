package com.ranobe.audioreader.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ranobe.audioreader.RanobeAudioApp
import com.ranobe.audioreader.data.remote.htmlToText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as RanobeAudioApp).container
        val chapterId = inputData.getLong("chapterId", -1)
        try {
            val chapter = checkNotNull(container.chapterDao.getById(chapterId)) { "Глава удалена" }
            if (chapter.html != null) return@withContext Result.success()
            val novel = checkNotNull(container.novelDao.getById(chapter.novelId)) { "Книга удалена" }
            val source = checkNotNull(container.sourceById(novel.sourceId)) { "Источник не найден" }
            val html = source.chapterContent(chapter.path)
            check(htmlToText(html).isNotBlank()) { "Источник вернул пустую главу. Проверьте доступ и авторизацию." }
            commitMutex.withLock {
                currentCoroutineContext().ensureActive()
                container.chapterDao.saveText(chapterId, html, System.currentTimeMillis())
            }
            setProgress(workDataOf("done" to 1, "total" to 1))
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Ошибка загрузки главы").take(1000)))
        }
    }

    companion object {
        internal val commitMutex = Mutex()

        fun enqueue(context: Context, chapterId: Long, novelId: Long, wifiOnly: Boolean) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                "text-$chapterId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                    .setInputData(workDataOf("chapterId" to chapterId))
                    .addTag("text").addTag("chapter-$chapterId").addTag("novel-$novelId")
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build())
                    .build(),
            )
    }
}
