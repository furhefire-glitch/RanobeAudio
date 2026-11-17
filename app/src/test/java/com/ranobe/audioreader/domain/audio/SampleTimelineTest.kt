package com.ranobe.audioreader.domain.audio

import org.junit.Assert.*
import org.junit.Test

class SampleTimelineTest {
    @Test fun roundingDoesNotAccumulate() {
        val clock = SampleTimeline(48000)
        var end = 0L
        repeat(10000) {
            val interval = clock.append(1001)
            assertEquals(end, interval.first)
            end = interval.second
        }
        assertEquals(1001L * 10000 * 1000 / 48000, end)
        assertEquals(end, clock.durationMs)
    }

    @Test fun emptyChunkDoesNotAdvanceClock() {
        val clock = SampleTimeline(24000)
        assertEquals(0L to 1000L, clock.append(24000))
        assertEquals(1000L to 1000L, clock.append(0))
    }

    @Test fun sampleCountExceedsIntRange() {
        val clock = SampleTimeline(48000)
        clock.append(Int.MAX_VALUE)
        clock.append(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE.toLong() * 2, clock.samples)
    }
}
