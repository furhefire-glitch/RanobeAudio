package com.ranobe.audioreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity

@Dao
interface ChunkTimingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(timings: List<ChunkTimingEntity>)

    @Query("DELETE FROM chunk_timings WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)

    @Query("SELECT * FROM chunk_timings WHERE chapterId = :chapterId ORDER BY chunkIndex")
    suspend fun getByChapter(chapterId: Long): List<ChunkTimingEntity>

    @Query("SELECT * FROM chunk_timings WHERE chapterId = :chapterId ORDER BY chunkIndex")
    fun observeByChapter(chapterId: Long): kotlinx.coroutines.flow.Flow<List<ChunkTimingEntity>>

    @Query("SELECT * FROM chunk_timings WHERE chapterId = :chapterId AND startMs <= :positionMs AND endMs > :positionMs LIMIT 1")
    suspend fun findAtPosition(chapterId: Long, positionMs: Long): ChunkTimingEntity?
}
