package com.ranobe.audioreader.domain.audio

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity
import com.ranobe.audioreader.data.remote.htmlToText
import com.ranobe.audioreader.domain.text.TextChunker
import com.ranobe.audioreader.domain.text.TextCleaner

class AudioRenderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(foregroundInfo(0, 0))
        AudioWorkCoordinator.mutex.withLock { renderChapter() }
    }

    private suspend fun renderChapter(): Result {
        val chapterId = inputData.getLong(KEY_CHAPTER, -1L)
        val novelId = inputData.getLong(KEY_NOVEL, -1L)
        val modelId = inputData.getString(KEY_MODEL) ?: "ru"
        val speaker = inputData.getString(KEY_SPEAKER) ?: "xenia"
        val sampleRate = inputData.getInt(KEY_SAMPLE_RATE, 48000)
        val bitrate = inputData.getInt(KEY_BITRATE, 48000)
        if (chapterId <= 0 || novelId <= 0 || !Regex("[a-z0-9_]{1,40}").matches(modelId)) return Result.failure()

        val container = (applicationContext as RanobeAudioApp).container
        val chapterDao = container.chapterDao
        val chapter = chapterDao.getById(chapterId) ?: return Result.failure()
        if (chapter.novelId != novelId) return Result.failure()
        val novel = container.novelDao.getById(novelId) ?: return Result.failure()
        val folder = container.audioFileManager.chapterAudioFile(novelId, chapterId).parentFile!!
        val output = File(folder, "$chapterId-$id.m4a")
        val temporary = File(folder, "$chapterId-$id.partial")
        val engine = SileroTtsEngine(applicationContext)
        var committed = false
        return try {
            chapterDao.setAudioStatus(chapterId, AudioStatus.RENDERING)
            val html = chapter.html ?: run {
                val source = checkNotNull(container.sourceById(novel.sourceId)) { "Источник не найден" }
                source.chapterContent(chapter.path).also { chapterDao.saveText(chapterId, it, System.currentTimeMillis()) }
            }
            val dictionary = container.stressDictionary.snapshot()
            val cleaned = dictionary.applyReplacements(TextCleaner.clean(htmlToText(html)))
            val chunks = TextChunker.chunk(cleaned, 500)
            check(chunks.isNotEmpty()) { "В главе нет текста" }
            engine.load(container.sileroModelManager.modelDir(modelId), sampleRate)
            val timings = ArrayList<ChunkTimingEntity>(chunks.size)
            val clock = SampleTimeline(sampleRate)
            val duration = PcmToM4aEncoder(sampleRate, temporary, bitrate).use { encoder ->
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    val samples = engine.synthesizeChunk(chunk.text, speaker, dictionary.userDict)
                    val interval = clock.append(samples.size)
                    timings.add(ChunkTimingEntity(
                        chapterId = chapterId, chunkIndex = index,
                        startCharIndex = chunk.startCharIndex, endCharIndex = chunk.endCharIndex,
                        startMs = interval.first, endMs = interval.second,
                    ))
                    encoder.write(samples)
                    setProgress(Data.Builder().putInt("done", index + 1).putInt("total", chunks.size).build())
                    setForeground(foregroundInfo(index + 1, chunks.size))
                }
                encoder.finish()
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(temporary.renameTo(output)) { "Не удалось сохранить аудио" }
                container.database.withTransaction {
                    check(chapterDao.getById(chapterId) != null) { "Глава удалена" }
                    container.chunkTimingDao.deleteByChapter(chapterId)
                    container.chunkTimingDao.insertAll(timings)
                    chapterDao.saveRenderMetadata(chapterId, cleaned, modelId, speaker, sampleRate, bitrate)
                    chapterDao.updateAudio(chapterId, AudioStatus.READY, output.absolutePath, output.length(), duration, System.currentTimeMillis())
                }
                committed = true
                chapter.audioPath?.let { old ->
                    val file = File(old)
                    if (file.canonicalFile.parentFile == folder.canonicalFile && file != output) file.delete()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (!committed) chapterDao.setAudioStatus(chapterId, if (chapter.audioPath != null) AudioStatus.READY else AudioStatus.NONE)
            }
            throw e
        } catch (e: Exception) {
            Log.e("AudioRenderWorker", "Не удалось озвучить главу $chapterId", e)
            if (!committed) chapterDao.setAudioStatus(chapterId, if (chapter.audioPath != null) AudioStatus.READY else AudioStatus.ERROR)
            Result.failure(Data.Builder().putString("error", e.message?.take(1000) ?: "Ошибка озвучки").build())
        } finally {
            engine.release()
            temporary.delete()
            if (!committed) output.delete()
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, RanobeAudioApp.CHANNEL_RENDER)
            .setContentTitle("Озвучка главы")
            .setContentText(if (total > 0) "Часть $done из $total" else "Подготовка…")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .setProgress(total, done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo((id.hashCode() and 0x7fffffff).coerceAtLeast(1), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo((id.hashCode() and 0x7fffffff).coerceAtLeast(1), notification)
        }
    }

    companion object {
        const val KEY_CHAPTER = "chapterId"
        const val KEY_NOVEL = "novelId"
        const val KEY_MODEL = "modelId"
        const val KEY_SPEAKER = "speaker"
        const val KEY_SAMPLE_RATE = "sampleRate"
        const val KEY_BITRATE = "bitrate"

        fun enqueue(
            context: Context,
            chapterId: Long,
            novelId: Long,
            modelId: String,
            speaker: String,
            sampleRate: Int = 48000,
            bitrate: Int = 48000,
            wifiOnly: Boolean = false,
            chargingOnly: Boolean = false,
        ): androidx.work.Operation {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
                .setRequiresCharging(chargingOnly)
                .build()
            val data = Data.Builder()
                .putLong(KEY_CHAPTER, chapterId)
                .putLong(KEY_NOVEL, novelId)
                .putString(KEY_MODEL, modelId)
                .putString(KEY_SPEAKER, speaker)
                .putInt(KEY_SAMPLE_RATE, sampleRate)
                .putInt(KEY_BITRATE, bitrate)
                .build()
            val request = OneTimeWorkRequestBuilder<AudioRenderWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .addTag("render").addTag("chapter-$chapterId").addTag("novel-$novelId")
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork("render-$chapterId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
