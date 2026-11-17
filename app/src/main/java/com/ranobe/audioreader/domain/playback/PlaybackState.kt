package com.ranobe.audioreader.domain.playback

data class PlaybackState(
    val chapterId: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepRemainingMs: Long = 0,
    val error: String? = null,
    val connected: Boolean = false,
)
