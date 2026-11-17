package com.ranobe.audioreader.data.local

import androidx.room.Embedded
import androidx.room.Relation
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity

data class ReaderChapter(
    @Embedded val chapter: ChapterEntity,
    @Relation(parentColumn = "id", entityColumn = "chapterId")
    val timings: List<ChunkTimingEntity>,
)
