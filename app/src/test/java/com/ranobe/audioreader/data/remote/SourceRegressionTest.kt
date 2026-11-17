package com.ranobe.audioreader.data.remote

import com.ranobe.audioreader.data.remote.sources.RanobeLibHtml
import com.ranobe.audioreader.data.remote.sources.RanobehubSource
import com.ranobe.audioreader.data.remote.sources.RlibAttachment
import com.ranobe.audioreader.data.remote.sources.RulateSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SourceRegressionTest {
    @Test fun hubPreservesAbsoluteAndResolvesRelativeUrls() {
        val source = RanobehubSource(OkHttpClient())
        listOf("https://cdn.example/cover.jpg?q=1", "http://cdn.example/cover.jpg").forEach {
            assertEquals(it, source.resolveUrl(it))
        }
        assertEquals("https://cdn.example/cover.jpg", source.resolveUrl("//cdn.example/cover.jpg"))
        assertEquals("https://ranobehub.org/api/media/1", source.resolveUrl("/api/media/1"))
        assertEquals("https://ranobehub.org/ranobe/1-book", source.webUrl("1-book"))
        assertEquals("https://ranobehub.org/ranobe/1-book/chapter/2", source.webUrl("1-book", "1-book/chapter/2"))
    }

    @Test fun ranobeLibPreservesNestedListsAndUnknownWrapperText() {
        val content = SourceJson.parseToJsonElement("""{
            "type":"doc","content":[{"type":"bulletList","content":[
                {"type":"listItem","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"Ранг <S> & сила"}]},
                    {"type":"orderedList","content":[{"type":"listItem","content":[
                        {"type":"unknownWrapper","content":[{"type":"text","text":"<script>текст</script>"}]}
                    ]}]}
                ]}
            ]}]
        }""")
        val html = RanobeLibHtml.contentToHtml(content, emptyList())
        val doc = Jsoup.parseBodyFragment(html)
        assertEquals(1, doc.select("ul > li > ol > li").size)
        assertEquals("Ранг <S> & сила <script>текст</script>", doc.body().text())
        assertTrue(doc.select("script, s").isEmpty())
        assertEquals("Ранг <S> & сила\n\n<script>текст</script>", htmlToText(html))
    }

    @Test fun ranobeLibEscapesAndWhitelistsDirectImageAttributes() {
        val caption = "\" onerror=\"alert(1) & <текст> '"
        val attrs = buildJsonObject {
            put("src", "//cdn.example/image.jpg?q=1&part='two'")
            put("alt", caption)
            put("title", caption)
            put("onerror", "alert(1)")
            put("style", "background:url(javascript:alert(1))")
        }
        val html = renderImage(attrs)
        val image = Jsoup.parseBodyFragment(html).selectFirst("img")!!
        assertEquals("https://cdn.example/image.jpg?q=1&part=%27two%27", image.attr("src"))
        assertEquals(caption, image.attr("alt"))
        assertEquals(caption, image.attr("title"))
        assertFalse(image.hasAttr("onerror"))
        assertFalse(image.hasAttr("style"))
        assertTrue(html.contains("&quot;"))
        assertTrue(html.contains("&#39;"))
        assertTrue(html.contains("&amp;"))
    }

    @Test fun ranobeLibRejectsUnsafeImageSchemesForBothRepresentations() {
        listOf("javascript:alert(1)", "data:image/svg+xml,test", "file:///sdcard/a.jpg",
            "content://media/1", "https://cdn.example/\nimage.jpg", "\\\\cdn.example\\a.jpg").forEach { source ->
            assertEquals("", renderImage(buildJsonObject { put("src", source) }))
            assertEquals("", renderImage(
                buildJsonObject { put("images", JsonArray(listOf(buildJsonObject { put("image", 7) }))) },
                listOf(RlibAttachment(id = JsonPrimitive(7), url = source)),
            ))
        }
    }

    @Test fun ranobeLibResolvesImagePathsAndAttachmentNames() {
        val attrs = buildJsonObject {
            put("images", JsonArray(listOf(buildJsonObject { put("image", "picture") })))
        }
        val html = renderImage(attrs, listOf(RlibAttachment(name = "picture", url = "/images/a.jpg?a=1&b='2'")))
        assertEquals("https://ranobelib.me/images/a.jpg?a=1&b=%272%27", Jsoup.parseBodyFragment(html).selectFirst("img")!!.attr("src"))
        assertEquals("<p>Старый HTML</p>", RanobeLibHtml.contentToHtml(JsonPrimitive("<p>Старый HTML</p>"), emptyList()))
    }

    @Test fun rulateLeavesDecompressionToOkHttpAndFallsBackToOriginalTitle() = runBlocking {
        val source = RulateSource(client { request ->
            assertNull(request.header("Accept-Encoding"))
            """{"status":"success","response":[
                {"id":1,"t_title":"  ","s_title":"Оригинал"},
                {"id":2,"t_title":"Перевод","s_title":"Другой"}
            ]}"""
        })
        assertEquals(listOf("Оригинал", "Перевод"), source.searchNovels("книга").map { it.name })
    }

    @Test fun rulateReportsFailedSearchInsteadOfEmptyResults() = runBlocking {
        val source = RulateSource(client { """{"status":"error","response":{"message":"private"}}""" })
        try {
            source.searchNovels("книга")
            fail("Expected IOException")
        } catch (error: IOException) {
            assertEquals("Rulate: не удалось загрузить список книг", error.message)
        }
        val empty = RulateSource(client { """{"status":"success","response":[]}""" })
        assertTrue(empty.searchNovels("нет").isEmpty())
    }

    @Test fun rulateDetailsUseNonblankTitleAndUnmodifiedChapterNames() = runBlocking {
        val source = RulateSource(client { request ->
            when (request.url.encodedPath) {
                "/api3/book" -> """{"response":{"t_title":"","s_title":"Оригинал"}}"""
                "/api3/bookChapters" -> """{"response":[
                    {"id":2,"title":"Глава","can_read":true,"subscription":0,"illustrated":true},
                    {"id":3,"title":"Закрытая","can_read":false,"subscription":0}
                ]}"""
                else -> error("Unexpected path")
            }
        })
        val novel = source.novelDetails("1")
        assertEquals("Оригинал", novel.name)
        assertEquals(listOf("Глава"), novel.chapters.map { it.name })
    }

    private fun renderImage(attrs: kotlinx.serialization.json.JsonObject, attachments: List<RlibAttachment> = emptyList()): String {
        val image = buildJsonObject { put("type", "image"); put("attrs", attrs) }
        val document = buildJsonObject { put("type", "doc"); put("content", JsonArray(listOf(image))) }
        return RanobeLibHtml.contentToHtml(document, attachments)
    }

    private fun client(body: (Request) -> String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request()).toResponseBody()).build()
        }.build()
}
