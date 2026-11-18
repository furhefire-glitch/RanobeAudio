package com.ranobe.audioreader.data.remote

import com.ranobe.audioreader.data.remote.sources.UniversalSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class UniversalSourceTest {
    private val toc = """
        <html><head><title>Сайт книг</title><meta property="og:image" content="/cover.jpg"></head>
        <body><nav><a href="/other">Другая книга</a></nav>
        <h1>Моя книга</h1>
        <ul>
          <li><a href="/book/1">Глава 1</a></li>
          <li><a href="/book/2">Глава 2</a></li>
          <li><a href="/book/3">Глава 3</a></li>
          <li><a href="/book/2">Глава 2 дубль</a></li>
          <li><a href="#top">Наверх</a></li>
          <li><a href="https://evil.example/x">Внешняя ссылка</a></li>
        </ul>
        <footer><a href="/book/99">Ссылка в подвале</a></footer>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><nav><a href="/book">Оглавление</a></nav>
        <script>alert(1)</script>
        <article><p>Текст главы один.</p><p>Текст главы два.</p></article>
        <footer>Подвал сайта</footer></body></html>
    """.trimIndent()

    private fun source(lastUrl: AtomicReference<String> = AtomicReference()): UniversalSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            lastUrl.set(chain.request().url.toString())
            val body = when (chain.request().url.encodedPath) {
                "/book" -> toc
                else -> chapterHtml
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        return UniversalSource(client, CustomSourceEntry("custom-test", "Мой сайт", "https://site.example"))
    }

    @Test fun detailsExtractTitleCoverAndCleanChapterList() = runBlocking {
        val novel = source().novelDetails("https://site.example/book")
        assertEquals("Моя книга", novel.name)
        assertEquals("https://site.example/cover.jpg", novel.coverUrl)
        assertEquals(
            listOf("https://site.example/book/1", "https://site.example/book/2", "https://site.example/book/3"),
            novel.chapters.map { it.path },
        )
        assertEquals(listOf(1f, 2f, 3f), novel.chapters.map { it.chapterNumber })
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), novel.chapters.map { it.name })
    }

    @Test fun relativeNovelPathResolvesAgainstBaseUrl() = runBlocking {
        val lastUrl = AtomicReference<String>()
        source(lastUrl).novelDetails("book")
        assertEquals("https://site.example/book", lastUrl.get())
    }

    @Test fun chapterContentKeepsArticleAndDropsChrome() = runBlocking {
        val html = source().chapterContent("https://site.example/book/1")
        assertTrue(html.contains("Текст главы один."))
        assertTrue(html.contains("Текст главы два."))
        assertFalse(html.contains("alert(1)"))
        assertFalse(html.contains("Подвал сайта"))
        assertFalse(html.contains("Оглавление"))
    }

    @Test fun catalogAndSearchReportUnsupportedInsteadOfSilentEmpty() = runBlocking {
        val source = source()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.searchNovels("книга") } }
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.popularNovels(1) } }
        Unit
    }

    @Test fun pageWithoutChapterLinksFailsWithClearMessage() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html><body><h1>Пусто</h1><p>Без ссылок</p></body></html>".toResponseBody()).build()
        }.build()
        val source = UniversalSource(client, CustomSourceEntry("custom-test", "Мой сайт", "https://site.example"))
        try {
            source.novelDetails("https://site.example/book")
            fail("Expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("оглавление"))
        }
    }
}
