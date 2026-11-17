package com.ranobe.audioreader.domain.text

object TextCleaner {
    private val defaultPatterns = listOf(
        Regex("""https?://\S+"""),
        Regex("""\[\d+]"""),
        Regex("""\*{3,}"""),
        Regex("""-{5,}"""),
        Regex("""={5,}"""),
    )

    fun clean(text: String, extraPatterns: List<Regex> = emptyList()): String {
        var result = text
        (defaultPatterns + extraPatterns).forEach { result = it.replace(result, " ") }
        return result
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
