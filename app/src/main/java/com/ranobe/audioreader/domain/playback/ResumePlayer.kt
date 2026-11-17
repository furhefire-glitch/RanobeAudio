package com.ranobe.audioreader.domain.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal class ResumePlayer(
    private val delegate: Player,
    private val resumePolicy: PlaybackResumePolicy,
    private val restartCompletedChapter: () -> Unit,
    private val beforeExplicitSeek: () -> Unit,
    private val afterExplicitSeek: () -> Unit,
    private val onSpeedRequested: () -> Unit,
) : ForwardingPlayer(delegate) {
    override fun play() = setPlayWhenReady(true)

    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            if (delegate.mediaItemCount == 0) return
            restartCompletedChapter()
            if (!delegate.playWhenReady) {
                resumePolicy.consumeResumePosition(delegate.currentPosition)?.let { delegate.seekTo(it) }
            }
            if (delegate.playbackState == Player.STATE_IDLE) delegate.prepare()
        } else if (delegate.playWhenReady) {
            resumePolicy.onPaused()
        }
        // Delegate directly so play() and setPlayWhenReady() cannot apply the rewind twice.
        delegate.playWhenReady = playWhenReady
    }

    override fun stop() {
        setPlayWhenReady(false)
        delegate.stop()
    }

    private inline fun explicitSeek(action: () -> Unit) {
        resumePolicy.onExplicitSeek()
        beforeExplicitSeek()
        action()
        afterExplicitSeek()
    }

    override fun seekTo(positionMs: Long) = explicitSeek { delegate.seekTo(positionMs.coerceAtLeast(0)) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = explicitSeek {
        delegate.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToDefaultPosition() = explicitSeek { delegate.seekToDefaultPosition() }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = explicitSeek {
        delegate.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekBack() = explicitSeek { delegate.seekBack() }

    override fun seekForward() = explicitSeek { delegate.seekForward() }

    override fun seekToPreviousMediaItem() = explicitSeek { delegate.seekToPreviousMediaItem() }

    override fun seekToPrevious() = explicitSeek { delegate.seekToPrevious() }

    override fun seekToNextMediaItem() = explicitSeek { delegate.seekToNextMediaItem() }

    override fun seekToNext() = explicitSeek { delegate.seekToNext() }

    @Suppress("DEPRECATION")
    override fun seekToPreviousWindow() = explicitSeek { delegate.seekToPreviousWindow() }

    @Suppress("DEPRECATION")
    override fun seekToNextWindow() = explicitSeek { delegate.seekToNextWindow() }

    @Suppress("DEPRECATION")
    override fun next() = explicitSeek { delegate.next() }

    override fun setPlaybackSpeed(speed: Float) = setPlaybackParameters(PlaybackParameters(PlaybackCommands.clampSpeed(speed)))

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        onSpeedRequested()
        delegate.playbackParameters = PlaybackParameters(PlaybackCommands.clampSpeed(playbackParameters.speed))
    }
}
