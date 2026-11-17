package com.ranobe.audioreader.ui.reader

import com.ranobe.audioreader.data.local.entity.ChunkTimingEntity
import org.junit.Assert.*
import org.junit.Test

class ReaderTextTest {
    private val text = "Один.\nДва."
    private val timings = listOf(
        ChunkTimingEntity(chapterId = 1, chunkIndex = 0, startCharIndex = 0, endCharIndex = 5, startMs = 0, endMs = 100),
        ChunkTimingEntity(chapterId = 1, chunkIndex = 1, startCharIndex = 6, endCharIndex = 10, startMs = 100, endMs = 200),
    )

    @Test fun exactChunkBoundaries() {
        val segments = ReaderText.segments(text, timings)
        assertEquals(0, ReaderText.activeIndex(segments, 0))
        assertEquals(0, ReaderText.activeIndex(segments, 99))
        assertEquals(1, ReaderText.activeIndex(segments, 100))
        assertEquals(-1, ReaderText.activeIndex(segments, 200))
        assertEquals(-1, ReaderText.activeIndex(segments, -1))
    }

    @Test fun zeroDurationChunkIsSkipped() {
        val segments = ReaderText.segments(text, listOf(timings[0].copy(endMs = 0), timings[1].copy(startMs = 0)))
        assertEquals(1, ReaderText.activeIndex(segments, 0))
    }

    @Test fun missingSnapshotNeverUsesOldOffsets() {
        val segments = ReaderText.segments("Другой текст", emptyList())
        assertTrue(segments.all { it.timing == null })
        assertEquals(-1, ReaderText.activeIndex(segments, 0))
    }

    @Test fun rejectsOutOfBoundsAndGaps() {
        assertFalse(ReaderText.validTimings(text, listOf(timings[0].copy(endCharIndex = 300))))
        assertFalse(ReaderText.validTimings(text, listOf(timings[0], timings[1].copy(startMs = 101))))
        assertFalse(ReaderText.validTimings(text, listOf(timings[0], timings[1].copy(startCharIndex = 7))))
    }

    @Test fun longPressSelectsOnlyTouchedWord() {
        assertEquals("замок", ReaderText.wordAt("Мой зам+ок!", 6))
        assertNull(ReaderText.wordAt("Мой замок!", 3))
        assertNull(ReaderText.wordAt("Мой замок!", 9))
        assertEquals("кто-то", ReaderText.wordAt("кто-то здесь", 4))
    }

    @Test fun binarySearchMatchesLinearScanAcrossManyChunks() {
        val segments = (0 until 1000).map { index ->
            ReaderSegment("Текст", index * 5, ChunkTimingEntity(
                chapterId = 1, chunkIndex = index, startCharIndex = index * 5,
                endCharIndex = index * 5 + 5, startMs = index * 37L, endMs = (index + 1) * 37L,
            ))
        }
        for (position in -1L..37001L step 13) {
            val expected = segments.indexOfFirst { position >= it.timing!!.startMs && position < it.timing.endMs }
            assertEquals(expected, ReaderText.activeIndex(segments, position))
        }
    }

    @Test fun formatsLongAudio() {
        assertEquals("0:00", ReaderText.time(-1))
        assertEquals("1:05", ReaderText.time(65000))
        assertEquals("1:01:01", ReaderText.time(3661000))
    }
}
