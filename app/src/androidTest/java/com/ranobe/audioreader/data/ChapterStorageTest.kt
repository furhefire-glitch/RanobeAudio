package com.ranobe.audioreader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ranobe.audioreader.data.local.AppDatabase
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.local.entity.NovelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterStorageTest {
    private lateinit var database: AppDatabase
    private var chapterId = 0L

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val novelId = database.novelDao().insert(NovelEntity(sourceId = "test", path = "book", name = "Книга"))
        chapterId = database.chapterDao().insertAll(listOf(ChapterEntity(novelId = novelId, path = "chapter", name = "Глава",
            html = "<p>Текст</p>", audioPath = "/old.m4a", audioStatus = AudioStatus.READY, positionMs = 4321, durationMs = 9000))).single()
    }

    @After fun close() { database.close() }

    @Test fun metadataRefreshPreservesLocalContent() = runBlocking {
        val dao = database.chapterDao()
        dao.updateMetadata(chapterId, "Новое название", 2f, "today", "Переводчик", 5)
        val chapter = dao.getById(chapterId)!!
        assertEquals("Новое название", chapter.name)
        assertEquals("<p>Текст</p>", chapter.html)
        assertEquals("/old.m4a", chapter.audioPath)
        assertEquals(4321L, chapter.positionMs)
        assertEquals(AudioStatus.READY, chapter.audioStatus)
    }

    @Test fun listenedTimeIsStableAndOldPlaybackCannotOverwriteNewAudio() = runBlocking {
        val dao = database.chapterDao()
        assertEquals(1, dao.savePlayback(chapterId, "/old.m4a", 9000, true, 1000))
        dao.savePlayback(chapterId, "/old.m4a", 9000, true, 2000)
        assertEquals(1000L, dao.getById(chapterId)!!.listenedAt)
        assertEquals(1, dao.getExpiredAudio(1000).size)
        assertTrue(dao.getExpiredAudio(999).isEmpty())
        dao.saveRenderMetadata(chapterId, "Новый текст", "ru", "xenia", 48000, 48000)
        dao.updateAudio(chapterId, AudioStatus.READY, "/new.m4a", 10, 10000, 3000)
        assertEquals(0, dao.savePlayback(chapterId, "/old.m4a", 9000, true, 4000))
        assertNull(dao.getById(chapterId)!!.listenedAt)
        assertFalse(dao.getById(chapterId)!!.isRead)
        assertEquals(0L, dao.getById(chapterId)!!.positionMs)
    }

    @Test fun textDeletionKeepsAudioAndAudioDeletionKeepsText() = runBlocking {
        val dao = database.chapterDao()
        dao.saveRenderMetadata(chapterId, "Текст", "ru", "xenia", 48000, 48000)
        dao.clearText(chapterId)
        assertNull(dao.getById(chapterId)!!.html)
        assertNull(dao.getById(chapterId)!!.renderedText)
        assertEquals("/old.m4a", dao.getById(chapterId)!!.audioPath)
        dao.saveText(chapterId, "Скачано снова", 2000)
        dao.clearAudio(chapterId)
        assertEquals("Скачано снова", dao.getById(chapterId)!!.html)
        assertNull(dao.getById(chapterId)!!.audioPath)
        assertNull(dao.getById(chapterId)!!.audioModelId)
    }

    @Test fun textStatisticsCountRenderedSnapshotWithoutHtml() = runBlocking {
        val dao = database.chapterDao()
        dao.clearText(chapterId)
        dao.saveRenderMetadata(chapterId, "Я", "ru", "xenia", 48000, 48000)
        assertEquals(2L, dao.textBytes())
    }
}
