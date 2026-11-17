package com.ranobe.audioreader.domain.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object PlaybackCommands {
    const val OPEN_CHAPTER = "com.ranobe.audioreader.OPEN_CHAPTER"
    const val SLEEP_TIMER = "com.ranobe.audioreader.SLEEP_TIMER"
    const val CHAPTER_ID = "chapterId"
    const val PLAY = "play"
    const val MINUTES = "minutes"
    const val SLEEP_DEADLINE = "sleepDeadlineElapsedRealtimeMs"
    const val ERROR = "playbackError"
    const val DURATION = "chapterDurationMs"

    val openChapter = SessionCommand(OPEN_CHAPTER, Bundle.EMPTY)
    val sleepTimer = SessionCommand(SLEEP_TIMER, Bundle.EMPTY)

    fun clampSpeed(speed: Float): Float = if (speed.isFinite()) speed.coerceIn(0.8f, 2.5f) else 1f
}

internal suspend fun <T> ListenableFuture<T>.awaitPlaybackResult(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(false) }
        addListener(
            {
                if (continuation.isActive) {
                    try {
                        continuation.resume(get())
                    } catch (error: ExecutionException) {
                        continuation.resumeWithException(error.cause ?: error)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            },
            executor,
        )
    }
