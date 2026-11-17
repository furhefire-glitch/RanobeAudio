package com.ranobe.audioreader.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ranobe.audioreader.data.local.dao.ChapterDao
import com.ranobe.audioreader.data.local.dao.ChunkTimingDao
import com.ranobe.audioreader.data.local.dao.DictionaryDao
import com.ranobe.audioreader.data.local.dao.NovelDao
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity
import com.ranobe.audioreader.data.local.entity.NovelEntity
import com.ranobe.audioreader.data.local.entity.ReplacementRuleEntity

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        ChunkTimingEntity::class,
        ReplacementRuleEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chunkTimingDao(): ChunkTimingDao
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        const val NAME = "ranobe_audio.db"
    }
}
