package com.ranobe.audioreader.domain.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class ResumePlayerTest {
    @Test fun playAndSetPlayWhenReadyDoNotDoubleRewind() {
        val fake = FakePlayer()
        val policy = PlaybackResumePolicy().apply { onChapterOpened(fake.position) }
        val player = wrapper(fake, policy)
        player.play()
        player.playWhenReady = true
        assertEquals(15000L, fake.position)
        assertEquals(1, fake.seeks)
    }

    @Test fun pauseThenTapPreservesChosenPosition() {
        val fake = FakePlayer().apply { ready = true }
        val policy = PlaybackResumePolicy()
        val player = wrapper(fake, policy)
        player.pause()
        player.seekTo(70000)
        player.play()
        assertEquals(70000L, fake.position)
        assertEquals(1, fake.seeks)
    }

    @Test fun bufferingDoesNotCountAsPause() {
        val fake = FakePlayer().apply { ready = true; playbackState = Player.STATE_BUFFERING }
        val player = wrapper(fake, PlaybackResumePolicy())
        player.play()
        assertEquals(20000L, fake.position)
        assertEquals(0, fake.seeks)
    }

    @Test fun pauseResumeFromHeadsetRewinds() {
        val fake = FakePlayer().apply { ready = true }
        val player = wrapper(fake, PlaybackResumePolicy())
        player.playWhenReady = false
        player.playWhenReady = true
        assertEquals(15000L, fake.position)
        assertEquals(1, fake.seeks)
    }

    private fun wrapper(fake: FakePlayer, policy: PlaybackResumePolicy) = ResumePlayer(
        fake.player, policy, {}, {}, {}, {},
    )

    private class FakePlayer : InvocationHandler {
        var position = 20000L
        var ready = false
        var playbackState = Player.STATE_READY
        var seeks = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java), this) as Player

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getMediaItemCount" -> 1
            "getCurrentPosition" -> position
            "getPlayWhenReady" -> ready
            "getPlaybackState" -> playbackState
            "setPlayWhenReady" -> { ready = args!![0] as Boolean; null }
            "seekTo" -> { position = args!!.last() as Long; seeks++; null }
            "prepare" -> { playbackState = Player.STATE_READY; null }
            "toString" -> "FakePlayer"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> error("Unexpected player call: ${method.name}")
        }
    }
}
