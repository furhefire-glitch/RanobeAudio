package com.ranobe.audioreader.domain.audio

class SampleTimeline(private val sampleRate: Int) {
    init { require(sampleRate > 0) }
    var samples: Long = 0L
        private set
    val durationMs: Long get() = samples * 1000L / sampleRate

    fun append(count: Int): Pair<Long, Long> {
        require(count >= 0)
        val start = durationMs
        samples += count
        return start to durationMs
    }
}
