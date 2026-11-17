package com.ranobe.audioreader.domain.text

import org.junit.Assert.*
import org.junit.Test

class TextChunkerTest {
    @Test fun blankTextHasNoChunks() {
        assertTrue(TextChunker.chunk(" \n\t ").isEmpty())
    }

    @Test fun chunksPreserveExactOffsets() {
        val text = "  Первое предложение.\n\nВторой абзац!   " + "Оченьдлинноеслово".repeat(90)
        val chunks = TextChunker.chunk(text, 100)
        var end = 0
        for (chunk in chunks) {
            assertEquals(chunk.text, text.substring(chunk.startCharIndex, chunk.endCharIndex))
            assertTrue(chunk.text.length <= 100)
            assertTrue(chunk.startCharIndex >= end)
            assertTrue(text.substring(end, chunk.startCharIndex).isBlank())
            assertFalse(chunk.text.first().isWhitespace())
            assertFalse(chunk.text.last().isWhitespace())
            end = chunk.endCharIndex
        }
        assertTrue(text.substring(end).isBlank())
    }

    @Test fun splitsAtSentenceWhenPossible() {
        val chunks = TextChunker.chunk("Первая фраза. Вторая фраза длиннее.", 22)
        assertEquals("Первая фраза.", chunks.first().text)
    }

    @Test fun surrogatePairsStayIntact() {
        val symbol = String(Character.toChars(0x1F600))
        val chunks = TextChunker.chunk("a" + symbol.repeat(20), 4)
        assertEquals("a" + symbol.repeat(20), chunks.joinToString("") { it.text })
        assertTrue(chunks.all { !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroLimit() { TextChunker.chunk("Текст", 0) }
}
