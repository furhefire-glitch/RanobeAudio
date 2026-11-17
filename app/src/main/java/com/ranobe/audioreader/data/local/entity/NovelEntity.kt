package com.ranobe.audioreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novels",
    indices = [Index(value = ["sourceId", "path"], unique = true)],
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val path: String,
    val name: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val status: String = "UNKNOWN",
    val rating: Float? = null,
    val inLibrary: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long? = null,
    val lastReadChapterId: Long? = null,
)
