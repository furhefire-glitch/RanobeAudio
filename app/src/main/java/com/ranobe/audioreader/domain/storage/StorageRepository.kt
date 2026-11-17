package com.ranobe.audioreader.domain.storage

import android.content.Context
import coil.imageLoader
import com.ranobe.audioreader.data.repository.DeleteMode
import com.ranobe.audioreader.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageStats(val audioBytes: Long, val textBytes: Long, val modelBytes: Long, val cacheBytes: Long, val freeBytes: Long)

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class StorageRepository(context: Context, private val container: AppContainer) {
    private val context = context.applicationContext

    suspend fun snapshot(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(
            container.audioFileManager.totalAudioBytes(), container.chapterDao.textBytes(),
            container.sileroModelManager.installed().sumOf { it.sizeBytes },
            context.imageLoader.diskCache?.size ?: 0L, container.audioFileManager.freeSpaceBytes(),
        )
    }

    suspend fun clearListenedAudio(): Long = withContext(Dispatchers.IO) {
        val chapters = container.chapterDao.getAllWithAudio().filter { it.isRead }
        container.libraryRepository.removeData(chapters.map { it.id }, DeleteMode.AUDIO)
    }

    suspend fun clearAllAudio(): Long = withContext(Dispatchers.IO) {
        val ids = container.novelDao.getAll().flatMap { container.chapterDao.getByNovel(it.id) }.map { it.id }
        container.libraryRepository.removeData(ids, DeleteMode.AUDIO, removeOrphans = true)
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val cache = context.imageLoader.diskCache
        val bytes = cache?.size ?: 0L
        cache?.clear()
        context.imageLoader.memoryCache?.clear()
        bytes
    }

    suspend fun clearExpiredAudio(hours: Int): Long = withContext(Dispatchers.IO) {
        if (hours <= 0) return@withContext 0L
        val before = System.currentTimeMillis() - hours * 3_600_000L
        val chapters = container.chapterDao.getExpiredAudio(before)
        container.libraryRepository.removeData(chapters.map { it.id }, DeleteMode.AUDIO, skipCurrent = true)
    }
}
