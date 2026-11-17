package com.ranobe.audioreader.domain.text

data class TextChunk(val text: String, val startCharIndex: Int, val endCharIndex: Int)

object TextChunker {
    fun chunk(text: String, maxLen: Int = 500): List<TextChunk> {
        require(maxLen >= 2)
        val result = mutableListOf<TextChunk>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            val limit = minOf(start + maxLen, text.length)
            var end = limit
            if (limit < text.length) {
                val boundary = (start until limit).lastOrNull { i ->
                    text[i] == '\n' || (text[i] in ".!?…" && text[i + 1].isWhitespace())
                }
                if (boundary != null) {
                    end = boundary + 1
                } else {
                    val space = (start until limit).lastOrNull { text[it].isWhitespace() }
                    if (space != null) end = space
                    else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                }
            }
            val next = end
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end > start) result.add(TextChunk(text.substring(start, end), start, end))
            start = next
        }
        return result
    }
}
