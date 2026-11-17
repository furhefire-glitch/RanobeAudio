package com.ranobe.audioreader.data.remote.sources

import com.ranobe.audioreader.data.remote.AuthStore
import com.ranobe.audioreader.data.remote.BrowseOptions
import com.ranobe.audioreader.data.remote.ChapterItem
import com.ranobe.audioreader.data.remote.NovelItem
import com.ranobe.audioreader.data.remote.NovelSource
import com.ranobe.audioreader.data.remote.NovelStatus
import com.ranobe.audioreader.data.remote.SourceNovel
import com.ranobe.audioreader.data.remote.fetchString
import com.ranobe.audioreader.data.remote.htmlToText
import com.ranobe.audioreader.data.remote.parseJson
import com.ranobe.audioreader.data.remote.urlEncode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.TimeZone

class RanobeLibSource(
    private val client: OkHttpClient,
    private val authStore: AuthStore,
) : NovelSource {
    override val id = "ranobelib"
    override val name = "RanobeLib"
    override val baseUrl = "https://ranobelib.me"
    override val requiresAuth = true

    private val apiSite = "https://api.cdnlibs.org/api/manga/"

    private val baseHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://ranobelib.me",
        "Origin" to "https://ranobelib.me/",
        "Site-Id" to "3",
        "client-time-zone" to TimeZone.getDefault().id,
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.0.0 Safari/537.36",
    )

    private fun headers(): Map<String, String> {
        val result = baseHeaders.toMutableMap()
        result["Host"] = "api.cdnlibs.org"
        authStore.ranobeLibToken?.let { result["Authorization"] = "Bearer $it" }
        return result
    }

    override suspend fun popularNovels(page: Int, options: BrowseOptions): List<NovelItem> {
        val sortBy = if (options.latest) "last_chapter_at" else options.sort ?: "rating_score"
        val url = StringBuilder("$apiSite?site_id[0]=3&page=$page&sort_by=$sortBy&sort_type=desc")
        if (options.filters["require_chapters"]?.firstOrNull() == "true") url.append("&chapters[min]=1")
        listOf("types", "scanlateStatus", "manga_status", "genres", "genres_exclude", "tags", "tags_exclude")
            .forEach { key ->
                options.filters[key]?.forEach { url.append("&").append(key).append("[]=").append(it) }
            }
        return parseNovels(url.toString())
    }

    override suspend fun searchNovels(query: String, page: Int): List<NovelItem> =
        parseNovels("$apiSite?site_id[0]=3&q=${urlEncode(query)}")

    private suspend fun parseNovels(url: String): List<NovelItem> {
        val result = client.fetchString(url, headers()).parseJson<RlibList>()
        return result.data.map {
            NovelItem(
                path = it.slug_url ?: "${it.id}--${it.slug}",
                name = it.rus_name ?: it.eng_name ?: it.name,
                coverUrl = it.cover?.default,
            )
        }
    }

    override suspend fun novelDetails(novelPath: String): SourceNovel {
        val fields = "fields[]=summary&fields[]=genres&fields[]=tags&fields[]=teams&fields[]=authors&fields[]=status_id&fields[]=artists"
        val data = client.fetchString("$apiSite$novelPath?$fields", headers()).parseJson<RlibDetail>().data

        val genres = (data.genres.orEmpty() + data.tags.orEmpty()).map { it.name }.filter { it.isNotBlank() }
        val branchNames = mutableMapOf("0" to "Главная страница")
        data.teams.orEmpty().forEach { branchNames[(it.details?.branch_id ?: 0L).toString()] = it.name }

        val chaptersData = client.fetchString("$apiSite$novelPath/chapters", headers()).parseJson<RlibChapters>()
        val chapters = chaptersData.data.flatMap { chapter ->
            chapter.branches.map { branch ->
                val bId = (branch.branch_id ?: 0L).toString()
                val suffix = chapter.name?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                ChapterItem(
                    path = "$novelPath/${chapter.volume}/${chapter.number}/$bId",
                    name = "Том ${chapter.volume} Глава ${chapter.number}$suffix",
                    chapterNumber = chapter.index.toFloat(),
                    releaseTime = branch.created_at,
                    scanlator = branchNames[bId] ?: "Неизвестный",
                )
            }
        }

        val status = when (data.status?.id) {
            1 -> NovelStatus.ONGOING
            2 -> NovelStatus.COMPLETED
            3 -> NovelStatus.ON_HIATUS
            4 -> NovelStatus.CANCELLED
            else -> NovelStatus.UNKNOWN
        }

        return SourceNovel(
            path = novelPath,
            name = data.rus_name ?: data.name,
            coverUrl = data.cover?.default,
            author = data.authors?.firstOrNull()?.name,
            artist = data.artists?.firstOrNull()?.name,
            genres = genres.joinToString(", ").ifBlank { null },
            summary = htmlToText(RanobeLibHtml.contentToHtml(data.summary, emptyList())).ifBlank { null },
            status = status,
            chapters = chapters,
        )
    }

    override suspend fun chapterContent(chapterPath: String): String {
        val parts = chapterPath.split("/")
        if (parts.size < 3) throw IOException("Неверный путь главы: $chapterPath")
        val slug = parts[0]
        val volume = parts[1]
        val number = parts[2]
        val branch = parts.getOrNull(3)
        val url = apiSite + slug + "/chapter?" +
            (branch?.let { "branch_id=$it&" } ?: "") + "number=$number&volume=$volume"
        val data = client.fetchString(url, headers()).parseJson<RlibDetail>().data
        val html = RanobeLibHtml.contentToHtml(data.content, data.attachments.orEmpty())
        if (html.isBlank()) throw IOException("Не удалось загрузить главу")
        return html
    }

    override fun webUrl(novelPath: String, chapterPath: String?): String {
        if (chapterPath == null) return "$baseUrl/ru/book/$novelPath"
        val parts = chapterPath.split("/")
        val slug = parts[0]
        val volume = parts.getOrElse(1) { "1" }
        val number = parts.getOrElse(2) { "1" }
        val branch = parts.getOrNull(3)
        return "$baseUrl/ru/$slug/read/v$volume/c$number" + (branch?.let { "?bid=$it" } ?: "")
    }

}

internal object RanobeLibHtml {
    private val imageBaseUrl = "https://ranobelib.me/".toHttpUrl()

    fun contentToHtml(content: JsonElement?, attachments: List<RlibAttachment>): String {
        if (content is JsonPrimitive && content.isString) return content.content
        if (content is JsonObject && content.string("type") == "doc") {
            return jsonToHtml(content["content"] as? JsonArray ?: JsonArray(emptyList()), attachments)
        }
        return ""
    }

    private fun jsonToHtml(nodes: JsonArray, attachments: List<RlibAttachment>): String {
        val sb = StringBuilder()
        nodes.forEach { element ->
            val node = element as? JsonObject ?: return@forEach
            when (node.string("type")) {
                "hardBreak" -> sb.append("<br>")
                "horizontalRule" -> sb.append("<hr>")
                "image" -> appendImage(sb, node, attachments)
                "paragraph" -> sb.append("<p>").append(childHtml(node, attachments)).append("</p>")
                "bulletList" -> sb.append("<ul>").append(childHtml(node, attachments)).append("</ul>")
                "orderedList" -> sb.append("<ol>").append(childHtml(node, attachments)).append("</ol>")
                "listItem" -> sb.append("<li>").append(childHtml(node, attachments)).append("</li>")
                "blockquote" -> sb.append("<blockquote>").append(childHtml(node, attachments)).append("</blockquote>")
                "italic" -> sb.append("<i>").append(childHtml(node, attachments)).append("</i>")
                "bold" -> sb.append("<b>").append(childHtml(node, attachments)).append("</b>")
                "underline" -> sb.append("<u>").append(childHtml(node, attachments)).append("</u>")
                "heading" -> sb.append("<h2>").append(childHtml(node, attachments)).append("</h2>")
                "text" -> sb.append(escape(node.string("text").orEmpty()))
                else -> {
                    sb.append(escape(node.string("text").orEmpty()))
                    (node["content"] as? JsonArray)?.let { sb.append(jsonToHtml(it, attachments)) }
                }
            }
        }
        return sb.toString()
    }

    private fun childHtml(node: JsonObject, attachments: List<RlibAttachment>): String {
        val content = node["content"] as? JsonArray ?: return "<br>"
        return jsonToHtml(content, attachments)
    }

    private fun appendImage(sb: StringBuilder, node: JsonObject, attachments: List<RlibAttachment>) {
        val attrs = node["attrs"] as? JsonObject ?: return
        val images = attrs["images"] as? JsonArray
        if (!images.isNullOrEmpty()) {
            images.forEach { img ->
                val ref = (img as? JsonObject)?.string("image") ?: return@forEach
                val file = attachments.firstOrNull { it.name == ref || (it.id as? JsonPrimitive)?.contentOrNull == ref }
                file?.let { appendImageTag(sb, it.url, attrs) }
            }
        } else {
            attrs.string("src")?.let { appendImageTag(sb, it, attrs) }
        }
    }

    private fun appendImageTag(sb: StringBuilder, source: String, attrs: JsonObject) {
        if (source.isBlank() || source.any { it.isISOControl() || it == '\\' }) return
        val url = imageBaseUrl.resolve(source) ?: return
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return
        sb.append("<img src=\"").append(escape(url.toString())).append('"')
        listOf("alt", "title").forEach { name ->
            attrs.string(name)?.let { sb.append(' ').append(name).append("=\"").append(escape(it)).append('"') }
        }
        sb.append('>')
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}

@Serializable
data class RlibList(val data: List<RlibManga> = emptyList())

@Serializable
data class RlibManga(
    val id: Long = 0,
    val name: String = "",
    val rus_name: String? = null,
    val eng_name: String? = null,
    val slug: String = "",
    val slug_url: String? = null,
    val cover: RlibCover? = null,
)

@Serializable
data class RlibCover(val default: String? = null, val thumbnail: String? = null)

@Serializable
data class RlibDetail(val data: RlibData)

@Serializable
data class RlibData(
    val id: Long = 0,
    val name: String = "",
    val rus_name: String? = null,
    val cover: RlibCover? = null,
    val summary: JsonElement? = null,
    val status: RlibIdName? = null,
    val authors: List<RlibName>? = null,
    val artists: List<RlibName>? = null,
    val genres: List<RlibName>? = null,
    val tags: List<RlibName>? = null,
    val teams: List<RlibTeam>? = null,
    val content: JsonElement? = null,
    val attachments: List<RlibAttachment>? = null,
)

@Serializable
data class RlibIdName(val id: Int = 0, val label: String? = null)

@Serializable
data class RlibName(val id: Long = 0, val name: String = "")

@Serializable
data class RlibTeam(val id: Long = 0, val name: String = "", val details: RlibTeamDetails? = null)

@Serializable
data class RlibTeamDetails(val branch_id: Long? = null)

@Serializable
data class RlibChapters(val data: List<RlibChapter> = emptyList())

@Serializable
data class RlibChapter(
    val id: Long = 0,
    val index: Int = 0,
    val volume: String = "",
    val number: String = "",
    val name: String? = null,
    val branches: List<RlibBranch> = emptyList(),
)

@Serializable
data class RlibBranch(val branch_id: Long? = null, val created_at: String? = null)

@Serializable
data class RlibAttachment(val id: JsonElement? = null, val name: String = "", val url: String = "")
