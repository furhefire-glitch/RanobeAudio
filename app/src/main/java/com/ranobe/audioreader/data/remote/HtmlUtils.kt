package com.ranobe.audioreader.data.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    val doc = Jsoup.parse(html)
    doc.outputSettings().prettyPrint(false)
    doc.select("br").forEach { it.replaceWith(TextNode("\n")) }
    doc.select("p, div, li, h1, h2, h3, h4, h5, h6, blockquote, hr").forEach { it.append("\n\n") }
    val text = doc.body()?.wholeText() ?: doc.wholeText()
    return text
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
