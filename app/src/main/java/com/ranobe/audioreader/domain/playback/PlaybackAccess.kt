package com.ranobe.audioreader.domain.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

object PlaybackAccess {
    val mutex = Mutex()
    @Volatile var currentChapterId: Long? = null
    var detachHandler: ((Set<Long>) -> Unit)? = null

    suspend fun detach(ids: Set<Long>) = withContext(Dispatchers.Main.immediate) {
        detachHandler?.invoke(ids)
    }
}
