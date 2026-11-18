package com.ranobe.audioreader.data.remote.sources

import com.ranobe.audioreader.data.remote.BrowseOptions
import com.ranobe.audioreader.data.remote.ChapterItem
import com.ranobe.audioreader.data.remote.CustomSourceEntry
import com.ranobe.audioreader.data.remote.NovelItem
import com.ranobe.audioreader.data.remote.NovelSource
import com.ranobe.audioreader.data.remote.SourceNovel
import com.ranobe.audioreader.data.remote.fetchString
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class UniversalSource(private val client: OkHttpClient, entry: CustomSourceEntry) : NovelSource {
    override val id = entry.id
    override val name = entry.name
    override val baseUrl = entry.baseUrl
    override val requiresAuth = false

    override suspend fun popularNovels(page: Int, options: BrowseOptions): List<NovelItem> =
        throw IOException("У своего источника нет каталога. Откройте книгу по ссылке на оглавление.")

    override suspend fun searchNovels(query: String, page: Int): List<NovelItem> =
        throw IOException("Этот сайт не поддерживает поиск из приложения. Вставьте ссылку на оглавление книги.")

    override suspend fun novelDetails(novelPath: String): SourceNovel {
        val url = resolve(novelPath)
        val doc = Jsoup.parse(client.fetchString(url), url)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().trim().takeIf { it.isNotEmpty() }
            ?: throw IOException("Не удалось определить название книги на странице")
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URI(url).resolve(it).toString() }.getOrNull() }
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val summary = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val host = runCatching { URI(url).host }.getOrNull() ?: throw IOException("Некорректная ссылка на книгу")
        val links = candidateLinks(chapterScope(doc), url, host)
        if (links.isEmpty()) throw IOException("На странице не найдено ссылок на главы. Откройте именно оглавление книги.")
        return SourceNovel(
            path = url,
            name = title,
            coverUrl = cover,
            author = author,
            summary = summary,
            chapters = links.mapIndexed { index, chapter -> chapter.copy(chapterNumber = (index + 1).toFloat()) },
        )
    }

    override suspend fun chapterContent(chapterPath: String): String {
        val url = resolve(chapterPath)
        val doc = Jsoup.parse(client.fetchString(url), url)
        doc.select("script, style, noscript, nav, header, footer, aside, iframe, form, button, svg").remove()
        val content = doc.selectFirst("article, main, [role=main], .chapter-content, .entry-content, #content, .content, .text")
            ?: doc.body()
        val html = content?.html()?.trim().orEmpty()
        if (html.isEmpty()) throw IOException("Страница главы пустая или защищена от чтения")
        return html
    }

    override fun webUrl(novelPath: String, chapterPath: String?): String = chapterPath ?: novelPath

    private fun resolve(path: String): String =
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) path
        else "$baseUrl/${path.trimStart('/')}"

    private fun chapterScope(doc: Document): Element {
        val best = doc.select("ol, ul, table").maxByOrNull { candidateLinks(it, doc.baseUri(), hostOf(doc.baseUri())).size }
        if (best != null && candidateLinks(best, doc.baseUri(), hostOf(doc.baseUri())).size >= 3) return best
        return doc.selectFirst("main, article, [role=main]") ?: doc.body() ?: doc
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

    private fun candidateLinks(scope: Element, pageUrl: String, host: String): List<ChapterItem> =
        scope.select("a[href]").mapNotNull { link ->
            if (link.closest("nav, header, footer, aside") != null) return@mapNotNull null
            val absolute = link.absUrl("href")
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return@mapNotNull null
            if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return@mapNotNull null
            if (host.isEmpty() || !uri.host.equals(host, true)) return@mapNotNull null
            if (absolute.substringBefore('#') == pageUrl.substringBefore('#')) return@mapNotNull null
            val text = link.text().trim()
            if (text.isEmpty() || text.length > 200) return@mapNotNull null
            ChapterItem(path = absolute, name = text)
        }.distinctBy { it.path }
}
