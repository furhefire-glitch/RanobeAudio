package com.ranobe.audioreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sortOrder")
    fun observeByNovel(novelId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sortOrder")
    suspend fun getByNovel(novelId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): ChapterEntity?

    @androidx.room.Transaction
    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observeReader(id: Long): Flow<com.ranobe.audioreader.data.local.ReaderChapter?>

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observeById(id: Long): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE html IS NOT NULL OR audioPath IS NOT NULL ORDER BY novelId, sortOrder, id")
    fun observeDownloaded(): Flow<List<ChapterEntity>>

    @Query("UPDATE chapters SET renderedText = :text, audioModelId = :modelId, audioSpeaker = :speaker, audioSampleRate = :sampleRate, audioBitrate = :bitrate, positionMs = 0, isRead = 0, listenedAt = NULL WHERE id = :id")
    suspend fun saveRenderMetadata(id: Long, text: String, modelId: String, speaker: String, sampleRate: Int, bitrate: Int)

    @Query("UPDATE chapters SET html = :html, textDownloadedAt = :time WHERE id = :id")
    suspend fun saveText(id: Long, html: String, time: Long)

    @Query(
        "UPDATE chapters SET audioStatus = :status, audioPath = :path, audioBytes = :bytes, " +
            "durationMs = :durationMs, renderedAt = :time WHERE id = :id"
    )
    suspend fun updateAudio(id: Long, status: AudioStatus, path: String?, bytes: Long, durationMs: Long, time: Long?)

    @Query("UPDATE chapters SET audioStatus = :status WHERE id = :id")
    suspend fun setAudioStatus(id: Long, status: AudioStatus)

    @Query("UPDATE chapters SET positionMs = :positionMs, isRead = CASE WHEN :finished THEN 1 ELSE isRead END, listenedAt = CASE WHEN :finished THEN COALESCE(listenedAt, :now) ELSE listenedAt END WHERE id = :id AND audioPath = :expectedPath")
    suspend fun savePlayback(id: Long, expectedPath: String?, positionMs: Long, finished: Boolean, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chapters SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE chapters SET isRead = :isRead, listenedAt = CASE WHEN :isRead THEN COALESCE(listenedAt, :now) ELSE NULL END WHERE id = :id")
    suspend fun setRead(id: Long, isRead: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE chapters SET audioPath = NULL, audioStatus = 'NONE', audioBytes = 0, durationMs = 0, positionMs = 0, renderedAt = NULL, renderedText = NULL, audioModelId = NULL, audioSpeaker = NULL, audioSampleRate = NULL, audioBitrate = NULL WHERE id = :id")
    suspend fun clearAudio(id: Long)

    @Query("UPDATE chapters SET name = :name, chapterNumber = :number, releaseTime = :release, scanlator = :scanlator, sortOrder = :order WHERE id = :id")
    suspend fun updateMetadata(id: Long, name: String, number: Float?, release: String?, scanlator: String?, order: Int)

    @Query("SELECT * FROM chapters WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE audioPath IS NOT NULL")
    suspend fun getAllWithAudio(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE isRead = 1 AND listenedAt IS NOT NULL AND listenedAt <= :before AND audioPath IS NOT NULL")
    suspend fun getExpiredAudio(before: Long): List<ChapterEntity>

    @Query("UPDATE chapters SET html = NULL, textDownloadedAt = NULL, renderedText = NULL WHERE id = :id")
    suspend fun clearText(id: Long)

    @Query("SELECT COALESCE(SUM(COALESCE(LENGTH(CAST(html AS BLOB)), 0) + COALESCE(LENGTH(CAST(renderedText AS BLOB)), 0)), 0) FROM chapters")
    suspend fun textBytes(): Long

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND audioPath IS NOT NULL")
    suspend fun getWithAudio(novelId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND isRead = 1 AND audioPath IS NOT NULL")
    suspend fun getListenedWithAudio(novelId: Long): List<ChapterEntity>

    @Query("SELECT COALESCE(SUM(audioBytes), 0) FROM chapters WHERE novelId = :novelId")
    fun observeAudioBytes(novelId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(audioBytes), 0) FROM chapters")
    fun observeTotalAudioBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId")
    fun observeChapterCount(novelId: Long): Flow<Int>
}
