package com.ranobe.audioreader.domain.playback

class PlaybackResumePolicy(var rewindMs: Long = 5_000L) {
    init {
        require(rewindMs >= 0)
    }

    var rewindPending: Boolean = false
        private set

    fun onChapterOpened(positionMs: Long) {
        rewindPending = positionMs > 0
    }

    fun onPaused() {
        rewindPending = true
    }

    fun onExplicitSeek() {
        rewindPending = false
    }

    fun onChapterTransition() {
        rewindPending = false
    }

    fun consumeResumePosition(positionMs: Long): Long? {
        if (!rewindPending) return null
        rewindPending = false
        return (positionMs.coerceAtLeast(0) - rewindMs).coerceAtLeast(0)
    }
}
