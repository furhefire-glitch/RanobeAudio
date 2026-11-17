package com.ranobe.audioreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ranobe.audioreader.data.local.entity.NovelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(novel: NovelEntity): Long

    @Update
    suspend fun update(novel: NovelEntity)

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun getById(id: Long): NovelEntity?

    @Query("SELECT * FROM novels WHERE id = :id")
    fun observeById(id: Long): Flow<NovelEntity?>

    @Query("SELECT * FROM novels ORDER BY name")
    suspend fun getAll(): List<NovelEntity>

    @Query("SELECT * FROM novels WHERE sourceId = :sourceId AND path = :path")
    suspend fun getBySourcePath(sourceId: String, path: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE inLibrary = 1 ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeLibrary(): Flow<List<NovelEntity>>

    @Query("SELECT COUNT(*) FROM novels WHERE inLibrary = 1")
    fun observeLibraryCount(): Flow<Int>

    @Query("UPDATE novels SET lastReadAt = :time, lastReadChapterId = :chapterId WHERE id = :id")
    suspend fun updateLastRead(id: Long, chapterId: Long, time: Long)

    @Query("UPDATE novels SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: Long, inLibrary: Boolean)

    @Delete
    suspend fun delete(novel: NovelEntity)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: Long)
}
