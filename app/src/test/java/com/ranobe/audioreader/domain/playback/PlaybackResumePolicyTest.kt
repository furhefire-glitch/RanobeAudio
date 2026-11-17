package com.ranobe.audioreader.domain.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test fun restoresPositionThenRewindsOnce() {
        val policy = PlaybackResumePolicy()
        policy.onChapterOpened(20000)
        assertEquals(15000L, policy.consumeResumePosition(20000))
        assertNull(policy.consumeResumePosition(15000))
    }

    @Test fun repeatedPauseCallbacksDoNotStackRewinds() {
        val policy = PlaybackResumePolicy()
        repeat(4) { policy.onPaused() }
        assertEquals(15000L, policy.consumeResumePosition(20000))
        assertNull(policy.consumeResumePosition(15000))
    }

    @Test fun explicitSeekWhilePausedWinsOverRewind() {
        val policy = PlaybackResumePolicy()
        policy.onPaused()
        policy.onExplicitSeek()
        assertNull(policy.consumeResumePosition(35000))
    }

    @Test fun automaticChapterTransitionNeverRewinds() {
        val policy = PlaybackResumePolicy()
        policy.onPaused()
        policy.onChapterTransition()
        assertNull(policy.consumeResumePosition(4000))
    }

    @Test fun resumeDoesNotGoBeforeStart() {
        val policy = PlaybackResumePolicy()
        policy.onPaused()
        assertEquals(0L, policy.consumeResumePosition(1200))
    }

    @Test fun settingCanDisableRewindWhilePaused() {
        val policy = PlaybackResumePolicy()
        policy.onPaused()
        policy.rewindMs = 0
        assertEquals(20000L, policy.consumeResumePosition(20000))
        policy.rewindMs = 5000
        policy.onPaused()
        assertEquals(15000L, policy.consumeResumePosition(20000))
    }

    @Test fun newChapterDoesNotNeedRewind() {
        val policy = PlaybackResumePolicy()
        policy.onChapterOpened(0)
        assertNull(policy.consumeResumePosition(0))
    }
}
