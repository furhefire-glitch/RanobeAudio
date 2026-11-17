package com.ranobe.audioreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AudioStatus { NONE, QUEUED, RENDERING, READY, ERROR }

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index(value = ["novelId", "path"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val path: String,
    val name: String,
    val chapterNumber: Float? = null,
    val releaseTime: String? = null,
    val scanlator: String? = null,
    val sortOrder: Int = 0,
    val html: String? = null,
    val textDownloadedAt: Long? = null,
    val audioPath: String? = null,
    val audioStatus: AudioStatus = AudioStatus.NONE,
    val audioBytes: Long = 0,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isRead: Boolean = false,
    val listenedAt: Long? = null,
    val renderedAt: Long? = null,
    val renderedText: String? = null,
    val audioModelId: String? = null,
    val audioSpeaker: String? = null,
    val audioSampleRate: Int? = null,
    val audioBitrate: Int? = null,
)
