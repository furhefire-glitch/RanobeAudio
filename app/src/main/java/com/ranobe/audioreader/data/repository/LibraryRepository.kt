package com.ranobe.audioreader.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.local.entity.NovelEntity
import com.ranobe.audioreader.data.remote.SourceNovel
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.audio.AudioRenderWorker
import com.ranobe.audioreader.domain.audio.AudioWorkCoordinator
import com.ranobe.audioreader.domain.playback.PlaybackAccess
import com.ranobe.audioreader.util.awaitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DeleteMode { AUDIO, TEXT, ALL }

class LibraryRepository(context: Context, private val container: AppContainer) {
    private val context = context.applicationContext
    private val jobs = WorkManager.getInstance(this.context)
    private val mutations = Mutex()
    private val novels get() = container.novelDao
    private val chapters get() = container.chapterDao
    val library: Flow<List<NovelEntity>> = novels.observeLibrary()

    fun observeNovel(id: Long) = novels.observeById(id)
    fun observeChapters(id: Long) = chapters.observeByNovel(id)

    suspend fun fetchNovel(sourceId: String, path: String): Long = withContext(Dispatchers.IO) {
        val source = requireNotNull(container.sourceById(sourceId)) { "Неизвестный источник" }
        val details = source.novelDetails(path)
        mutations.withLock { saveNovel(sourceId, details, path) }
    }

    suspend fun refreshNovel(id: Long) = withContext(Dispatchers.IO) {
        val novel = checkNotNull(novels.getById(id)) { "Книга удалена" }
        val details = checkNotNull(container.sourceById(novel.sourceId)).novelDetails(novel.path)
        mutations.withLock {
            checkNotNull(novels.getById(id)) { "Книга удалена" }
            saveNovel(novel.sourceId, details, novel.path)
        }
        Unit
    }

    private suspend fun saveNovel(sourceId: String, details: SourceNovel, requestedPath: String): Long =
        container.database.withTransaction {
            val existing = novels.getBySourcePath(sourceId, details.path)
                ?: novels.getBySourcePath(sourceId, requestedPath)
            val novel = (existing ?: NovelEntity(sourceId = sourceId, path = details.path, name = details.name, inLibrary = false)).copy(
                name = details.name, coverUrl = details.coverUrl, author = details.author,
                artist = details.artist, genres = details.genres, summary = details.summary,
                status = details.status.name, rating = details.rating,
            )
            val id = if (existing == null) novels.insert(novel) else {
                novels.update(novel)
                existing.id
            }
            val current = chapters.getByNovel(id).associateBy { it.path }
            val ordered = details.chapters.distinctBy { it.path }.groupBy { it.scanlator?.trim().orEmpty() }.values.flatten()
            ordered.forEachIndexed { order, chapter ->
                val saved = current[chapter.path]
                if (saved == null) {
                    chapters.insertAll(listOf(ChapterEntity(
                        novelId = id, path = chapter.path, name = chapter.name,
                        chapterNumber = chapter.chapterNumber, releaseTime = chapter.releaseTime,
                        scanlator = chapter.scanlator, sortOrder = order,
                    )))
                } else {
                    chapters.updateMetadata(saved.id, chapter.name, chapter.chapterNumber, chapter.releaseTime, chapter.scanlator, order)
                }
            }
            val visible = ordered.mapTo(hashSetOf()) { it.path }
            current.values.filter { it.path !in visible }.forEachIndexed { index, chapter ->
                chapters.updateMetadata(chapter.id, chapter.name, chapter.chapterNumber, chapter.releaseTime, chapter.scanlator, ordered.size + index)
            }
            id
        }

    suspend fun setInLibrary(id: Long, added: Boolean) = novels.setInLibrary(id, added)

    suspend fun enqueueText(chapterIds: List<Long>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val settings = container.settingsRepository.current()
            selected(chapterIds).filter { it.html == null }.forEach {
                ChapterDownloadWorker.enqueue(context, it.id, it.novelId, settings.wifiOnly).result.awaitResult()
            }
        }
    }

    suspend fun enqueueAudio(chapterIds: List<Long>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val settings = container.settingsRepository.current()
            val model = container.sileroModelManager.installed().firstOrNull { it.id == settings.modelId }
            checkNotNull(model) { "Сначала скачайте и выберите модель в разделе «Голоса»" }
            check(settings.speaker in model.speakers) { "Выберите голос установленной модели" }
            selected(chapterIds).forEach { chapter ->
                if (jobs.getWorkInfosForUniqueWork("render-${chapter.id}").awaitResult().any { !it.state.isFinished }) return@forEach
                withContext(NonCancellable) {
                    chapters.setAudioStatus(chapter.id, AudioStatus.QUEUED)
                    try {
                        AudioRenderWorker.enqueue(context, chapter.id, chapter.novelId, settings.modelId, settings.speaker,
                            settings.sampleRate, settings.bitrate, settings.wifiOnly, settings.chargingOnly).result.awaitResult()
                    } catch (error: Exception) {
                        chapters.setAudioStatus(chapter.id, chapter.audioStatus)
                        throw error
                    }
                }
            }
        }
    }

    suspend fun cancelJobs(chapterIds: List<Long>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            cancel(chapterIds)
            AudioWorkCoordinator.mutex.withLock { resetCancelled(chapterIds) }
        }
    }

    private suspend fun cancel(ids: List<Long>, audioOnly: Boolean = false) {
        ids.distinct().forEach {
            if (audioOnly) jobs.cancelUniqueWork("render-$it").result.awaitResult()
            else jobs.cancelAllWorkByTag("chapter-$it").result.awaitResult()
        }
    }

    suspend fun reconcileRenderJobs(ids: List<Long>) = mutations.withLock {
        ids.distinct().forEach { id ->
            if (jobs.getWorkInfosForUniqueWork("render-$id").awaitResult().none { !it.state.isFinished }) resetCancelled(listOf(id))
        }
    }

    private suspend fun resetCancelled(ids: List<Long>) {
        selected(ids).filter { it.audioStatus == AudioStatus.QUEUED || it.audioStatus == AudioStatus.RENDERING }.forEach {
            chapters.setAudioStatus(it.id, if (it.audioPath != null) AudioStatus.READY else AudioStatus.NONE)
        }
    }

    suspend fun deleteChapterData(ids: List<Long>, mode: DeleteMode) {
        removeData(ids, mode)
    }

    internal suspend fun removeData(ids: List<Long>, mode: DeleteMode, skipCurrent: Boolean = false, removeOrphans: Boolean = false): Long = withContext(Dispatchers.IO) {
        mutations.withLock {
            cancel(ids, audioOnly = mode == DeleteMode.AUDIO)
            AudioWorkCoordinator.mutex.withLock {
                PlaybackAccess.mutex.withLock {
                    val chosen = selected(ids).filter { !skipCurrent || it.id != PlaybackAccess.currentChapterId }
                    withContext(NonCancellable) {
                        resetCancelled(ids)
                        if (mode != DeleteMode.TEXT) PlaybackAccess.detach(chosen.mapTo(hashSetOf()) { it.id })
                        val files = if (mode == DeleteMode.TEXT) emptyList() else chosen.mapNotNull { container.audioFileManager.managedFile(it.audioPath) }
                        val bytes = files.sumOf { it.length() }
                        ChapterDownloadWorker.commitMutex.withLock {
                            container.database.withTransaction {
                                chosen.forEach {
                                    container.chunkTimingDao.deleteByChapter(it.id)
                                    if (mode != DeleteMode.TEXT) chapters.clearAudio(it.id)
                                    if (mode != DeleteMode.AUDIO) chapters.clearText(it.id)
                                }
                            }
                        }
                        files.forEach { check(!it.exists() || it.delete()) { "Не удалось удалить аудиофайл: ${it.name}" } }
                        bytes + if (removeOrphans) container.audioFileManager.removeOrphanedAudio(
                            chapters.getAllWithAudio().mapNotNull { it.audioPath }.toSet(),
                        ) else 0L
                    }
                }
            }
        }
    }

    suspend fun deleteNovel(id: Long) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val ids = chapters.getByNovel(id).map { it.id }
            cancel(ids)
            AudioWorkCoordinator.mutex.withLock {
                PlaybackAccess.mutex.withLock {
                    withContext(NonCancellable) {
                        PlaybackAccess.detach(ids.toSet())
                        ChapterDownloadWorker.commitMutex.withLock { novels.deleteById(id) }
                        container.audioFileManager.deleteNovelAudio(id)
                    }
                }
            }
        }
    }

    suspend fun markRead(ids: List<Long>, read: Boolean) = container.database.withTransaction {
        ids.distinct().forEach { chapters.setRead(it, read) }
    }

    private suspend fun selected(ids: List<Long>): List<ChapterEntity> {
        val unique = ids.distinct()
        val found = unique.chunked(500).flatMap { chapters.getByIds(it) }.associateBy { it.id }
        return unique.mapNotNull { found[it] }
    }
}
