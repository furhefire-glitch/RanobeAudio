package com.ranobe.audioreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunk_timings",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chapterId"), Index(value = ["chapterId", "chunkIndex"], unique = true)],
)
data class ChunkTimingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val chunkIndex: Int,
    val startCharIndex: Int,
    val endCharIndex: Int,
    val startMs: Long,
    val endMs: Long,
)
