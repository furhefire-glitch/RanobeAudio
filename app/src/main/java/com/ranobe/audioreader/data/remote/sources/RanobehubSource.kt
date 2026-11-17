package com.ranobe.audioreader.data.remote.sources

import com.ranobe.audioreader.data.remote.BrowseOptions
import com.ranobe.audioreader.data.remote.ChapterItem
import com.ranobe.audioreader.data.remote.NovelItem
import com.ranobe.audioreader.data.remote.NovelSource
import com.ranobe.audioreader.data.remote.NovelStatus
import com.ranobe.audioreader.data.remote.SourceNovel
import com.ranobe.audioreader.data.remote.fetchString
import com.ranobe.audioreader.data.remote.parseJson
import com.ranobe.audioreader.data.remote.urlEncode
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder

class RanobehubSource(private val client: OkHttpClient) : NovelSource {
    override val id = "ranobehub"
    override val name = "RanobeHub"
    override val baseUrl = "https://ranobehub.org"
    override val requiresAuth = false

    override suspend fun popularNovels(page: Int, options: BrowseOptions): List<NovelItem> {
        val sort = if (options.latest) "updated" else options.sort ?: "popular"
        val params = StringBuilder("page=$page")
        if (sort != "updated") params.append("&sort=$sort")
        options.filters["country"]?.forEach { params.append("&country=$it") }
        options.filters["tag"]?.forEach { params.append("&tag=$it") }
        options.filters["event"]?.forEach { params.append("&event=$it") }
        options.filters["status"]?.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { params.append("&status=$it") }
        val result = client.fetchString("$baseUrl/api/catalog?$params").parseJson<CatalogResponse>()
        return result.items.map { it.toNovelItem() }
    }

    override suspend fun searchNovels(query: String, page: Int): List<NovelItem> {
        val result = client.fetchString("$baseUrl/api/search?q=${urlEncode(query)}").parseJson<HubSearchResponse>()
        return result.books.map { it.toNovelItem() }
    }

    private fun Book.toNovelItem() = NovelItem(
        path = "$id-$slug",
        name = title.ifBlank { originalTitle },
        coverUrl = resolveUrl(posterUrl),
    )

    override suspend fun novelDetails(novelPath: String): SourceNovel {
        val id = novelPath.substringBefore("-")
        val page = client.fetchString(resolveUrl(novelPath))
        val doc = Jsoup.parse(page)

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val summary = doc.selectFirst("#book-description")?.text()?.trim().orEmpty()
        val coverPath = Regex("""<img[^>]+src="/_next/image\?url=([^"&]+)""").find(page)?.groupValues?.get(1)
        val cover = coverPath?.let { resolveUrl(URLDecoder.decode(it, "UTF-8")) }
        val author = Regex("""book-author-byline[\s\S]*?<strong[^>]*>([\s\S]*?)</strong>""")
            .find(page)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()

        var genres: String? = null
        Regex("""\\"genres\\":(\[.*?\]),\\"labels\\":""").find(page)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                genres = raw.replace("\\\"", "\"").parseJson<List<HubGenre>>().joinToString(", ") { it.title }
            }
        }

        val statusId = Regex("""book-kicker-status"[^>]*href="/ranobe\?status=(\d+)""")
            .find(page)?.groupValues?.get(1)?.toIntOrNull()
        val status = when (statusId) {
            1 -> NovelStatus.ONGOING
            2 -> NovelStatus.COMPLETED
            3 -> NovelStatus.ON_HIATUS
            else -> NovelStatus.UNKNOWN
        }

        val first = client.fetchString("$baseUrl/api/books/$id/chapters?limit=$CHUNK_SIZE&offset=0")
            .parseJson<HubChaptersResponse>()
        val items = first.items.toMutableList()
        var offset = CHUNK_SIZE
        while (offset < first.total) {
            val rest = client.fetchString("$baseUrl/api/books/$id/chapters?limit=$CHUNK_SIZE&offset=$offset")
                .parseJson<HubChaptersResponse>()
            items.addAll(rest.items)
            offset += CHUNK_SIZE
        }
        val chapters = items.mapIndexed { index, chapter ->
            ChapterItem(
                path = "$novelPath/chapter/${chapter.id}",
                name = chapter.title,
                chapterNumber = (index + 1).toFloat(),
                releaseTime = chapter.changedAt,
            )
        }

        return SourceNovel(
            path = novelPath,
            name = title,
            coverUrl = cover,
            author = author?.ifBlank { null },
            genres = genres?.ifBlank { null },
            summary = summary.ifBlank { null },
            status = status,
            chapters = chapters,
        )
    }

    override suspend fun chapterContent(chapterPath: String): String {
        val id = chapterPath.substringAfterLast("/")
        val html = client.fetchString("$baseUrl/api/chapters/$id").parseJson<ChapterResponse>().chapter.html
        return html.replace(Regex("""src="/(api/media/[^"]*)""")) { "src=\"$baseUrl/${it.groupValues[1]}\"" }
    }

    override fun webUrl(novelPath: String, chapterPath: String?): String {
        val path = chapterPath ?: novelPath
        return resolveUrl(path)
    }

    internal fun resolveUrl(path: String): String = when {
        path.startsWith("https://", ignoreCase = true) || path.startsWith("http://", ignoreCase = true) -> path
        path.startsWith("//") -> "${baseUrl.substringBefore(":")}:$path"
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/ranobe/$path"
    }

    private companion object {
        const val CHUNK_SIZE = 250
    }
}

@Serializable
data class Book(
    val id: Long = 0,
    val slug: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val posterUrl: String = "",
)

@Serializable
data class HubGenre(val id: Long = 0, val title: String = "")

@Serializable
data class CatalogResponse(val items: List<Book> = emptyList())

@Serializable
data class HubSearchResponse(val books: List<Book> = emptyList())

@Serializable
data class HubChaptersResponse(val items: List<HChapter> = emptyList(), val total: Int = 0)

@Serializable
data class HChapter(val id: Long = 0, val title: String = "", val changedAt: String? = null)

@Serializable
data class ChapterResponse(val chapter: HChapterHtml)

@Serializable
data class HChapterHtml(val html: String = "")
