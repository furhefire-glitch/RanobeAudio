package com.ranobe.audioreader.data.remote.sources

import com.ranobe.audioreader.data.remote.BrowseOptions
import com.ranobe.audioreader.data.remote.ChapterItem
import com.ranobe.audioreader.data.remote.NovelItem
import com.ranobe.audioreader.data.remote.NovelSource
import com.ranobe.audioreader.data.remote.NovelStatus
import com.ranobe.audioreader.data.remote.SourceNovel
import com.ranobe.audioreader.data.remote.SourceJson
import com.ranobe.audioreader.data.remote.fetchString
import com.ranobe.audioreader.data.remote.htmlToText
import com.ranobe.audioreader.data.remote.parseJson
import com.ranobe.audioreader.data.remote.urlEncode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RulateSource(private val client: OkHttpClient) : NovelSource {
    override val id = "rulate"
    override val name = "Rulate"
    override val baseUrl = "https://tl.rulate.ru"
    override val requiresAuth = true

    private val headers = mapOf(
        "User-Agent" to "RuLateApp Android",
    )

    override suspend fun popularNovels(page: Int, options: BrowseOptions): List<NovelItem> {
        val sort = if (options.latest) "4" else options.sort ?: "6"
        val url = StringBuilder("$baseUrl/api3/searchBooks?limit=40&page=$page&sort=$sort")
        options.filters.forEach { (type, values) ->
            values.forEach { url.append("&").append(type).append("[]=").append(it) }
        }
        url.append("&key=").append(KEY)
        return parseNovels(url.toString())
    }

    override suspend fun searchNovels(query: String, page: Int): List<NovelItem> {
        val url = "$baseUrl/api3/searchBooks?t=${urlEncode(query)}&limit=40&page=$page&key=$KEY"
        return parseNovels(url)
    }

    private suspend fun parseNovels(url: String): List<NovelItem> {
        val payload = SourceJson.parseToJsonElement(client.fetchString(url, headers)).jsonObject
        if ((payload["status"] as? JsonPrimitive)?.contentOrNull != "success") {
            throw IOException("Rulate: не удалось загрузить список книг")
        }
        val data = SourceJson.decodeFromJsonElement<RulateSearchResponse>(payload)
        return data.response.orEmpty().map {
            NovelItem(path = it.id.toString(), name = it.t_title?.takeIf(String::isNotBlank) ?: it.s_title, coverUrl = it.img)
        }
    }

    override suspend fun novelDetails(novelPath: String): SourceNovel {
        val book = client.fetchString("$baseUrl/api3/book?book_id=$novelPath&key=$KEY", headers)
            .parseJson<BookResponse>().response
        val genres = (book.genres.orEmpty().mapNotNull { it.title } + book.tags.orEmpty().mapNotNull { it.name })
            .joinToString(",")
        val rating = book.rate?.takeIf { it.count > 0 }?.let {
            ((it.sum / it.count) * 100).toInt() / 100f
        }
        val chaptersData = client.fetchString("$baseUrl/api3/bookChapters?book_id=$novelPath&key=$KEY", headers)
            .parseJson<RulateChaptersResponse>()
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
        val chapters = chaptersData.response.orEmpty()
            .filter { it.can_read && it.subscription == 0 }
            .map {
                ChapterItem(
                    path = "$novelPath/${it.id}",
                    name = it.title,
                    chapterNumber = it.ord,
                    releaseTime = it.cdate?.let { ts -> dateFormat.format(Date(ts * 1000)) },
                )
            }
        return SourceNovel(
            path = novelPath,
            name = book.t_title?.takeIf(String::isNotBlank) ?: book.s_title,
            coverUrl = book.img,
            author = book.author,
            genres = genres.ifBlank { null },
            summary = htmlToText(book.description.orEmpty()).ifBlank { null },
            status = if (book.status == "Завершён") NovelStatus.COMPLETED else NovelStatus.ONGOING,
            rating = rating,
            chapters = chapters,
        )
    }

    override suspend fun chapterContent(chapterPath: String): String {
        val (book, chapter) = chapterPath.split("/")
        val url = "$baseUrl/api3/chapter?book_id=$book&chapter_id=$chapter&key=$KEY"
        return client.fetchString(url, headers).parseJson<ChapterTextResponse>().response.text
    }

    override fun webUrl(novelPath: String, chapterPath: String?): String =
        if (chapterPath != null) "$baseUrl/book/$chapterPath/ready_new" else "$baseUrl/book/$novelPath/"

    private companion object {
        const val KEY = "fpoiKLUues81werht039"
    }
}

@Serializable
data class RulateSearchResponse(val status: String = "", val response: List<RBook>? = null)

@Serializable
data class RBook(val id: Long = 0, val s_title: String = "", val t_title: String? = null, val img: String? = null)

@Serializable
data class BookResponse(val response: RBookDetail)

@Serializable
data class RBookDetail(
    val t_title: String? = null,
    val s_title: String = "",
    val img: String? = null,
    val genres: List<RulateGenre>? = null,
    val tags: List<RTag>? = null,
    val description: String? = null,
    val author: String? = null,
    val status: String? = null,
    val rate: RRate? = null,
)

@Serializable
data class RulateGenre(val title: String? = null)

@Serializable
data class RTag(val name: String? = null)

@Serializable
data class RRate(val sum: Double = 0.0, val count: Int = 0)

@Serializable
data class RulateChaptersResponse(val response: List<RChapter>? = null)

@Serializable
data class RChapter(
    val title: String = "",
    val id: Long = 0,
    val ord: Float? = null,
    val cdate: Long? = null,
    val subscription: Int = 0,
    val can_read: Boolean = false,
    val illustrated: Boolean? = null,
)

@Serializable
data class ChapterTextResponse(val response: RText)

@Serializable
data class RText(val text: String = "")
