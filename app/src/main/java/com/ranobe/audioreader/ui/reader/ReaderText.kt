package com.ranobe.audioreader.ui.reader

import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity
import com.ranobe.audioreader.domain.text.TextChunker

data class ReaderSegment(val text: String, val startChar: Int, val timing: ChunkTimingEntity?)

object ReaderText {
    fun segments(text: String, timings: List<ChunkTimingEntity>): List<ReaderSegment> {
        if (!validTimings(text, timings)) {
            return TextChunker.chunk(text).map { ReaderSegment(it.text, it.startCharIndex, null) }
        }
        return timings.map { ReaderSegment(text.substring(it.startCharIndex, it.endCharIndex), it.startCharIndex, it) }
    }

    fun validTimings(text: String, timings: List<ChunkTimingEntity>): Boolean {
        if (timings.isEmpty()) return false
        var endChar = 0
        var endMs = 0L
        timings.forEachIndexed { index, timing ->
            if (timing.chunkIndex != index || timing.startCharIndex < endChar ||
                timing.endCharIndex <= timing.startCharIndex || timing.endCharIndex > text.length ||
                timing.startMs != endMs || timing.endMs < timing.startMs ||
                text.substring(endChar, timing.startCharIndex).isNotBlank()) return false
            endChar = timing.endCharIndex
            endMs = timing.endMs
        }
        return text.substring(endChar).isBlank()
    }

    fun activeIndex(segments: List<ReaderSegment>, positionMs: Long): Int {
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val timing = segments[mid].timing ?: return -1
            when {
                positionMs < timing.startMs -> high = mid - 1
                positionMs >= timing.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun wordAt(text: String, offset: Int): String? {
        if (offset !in text.indices) return null
        return Regex("[+\\p{L}]+(?:[-’'][+\\p{L}]+)*").findAll(text)
            .firstOrNull { offset in it.range }?.value?.replace("+", "")?.takeIf { it.isNotBlank() }
    }

    fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
