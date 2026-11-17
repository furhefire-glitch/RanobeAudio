package com.ranobe.audioreader.data.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HttpUtilsTest {
    @Test fun closesSuccessfulResponseBody() = runBlocking {
        val body = trackedBody("Текст")
        val client = responseClient(body)
        try {
            assertEquals("Текст", client.fetchString("https://example.test/chapter"))
            assertTrue(body.closed.await(5, TimeUnit.SECONDS))
        } finally {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun preservesHttpStatusWithoutUrlOrBodySecrets() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            val body = trackedBody("private-body")
            val client = responseClient(body, code)
            try {
                try {
                    client.fetchString("https://example.test/private-path?key=private-key")
                    fail("Expected IOException")
                } catch (error: IOException) {
                    assertEquals("HTTP $code", error.message)
                    assertFalse(error.toString().contains("private"))
                }
                assertTrue(body.closed.await(5, TimeUnit.SECONDS))
            } finally {
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun cancelsCallWhileWaitingForResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val body = trackedBody("Текст")
        val client = responseClient(body, call = call, beforeResponse = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            client.fetchString("https://example.test/chapter")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
        }
        try {
            assertTrue(body.closed.await(5, TimeUnit.SECONDS))
        } finally {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun cancelsCallDuringBodyReadAndClosesBody() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val body = trackedBody("Текст") {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val client = responseClient(body, call = call)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            client.fetchString("https://example.test/chapter")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
        }
        try {
            assertTrue(body.closed.await(5, TimeUnit.SECONDS))
        } finally {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun networkErrorsDoNotExposeRequestDetails() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            throw IOException("https://example.test/?token=private-token")
        }.build()
        try {
            try {
                client.fetchString("https://example.test/")
                fail("Expected IOException")
            } catch (error: IOException) {
                assertEquals("Ошибка сети при загрузке", error.message)
                assertFalse(error.stackTraceToString().contains("private-token"))
                assertFalse(error.stackTraceToString().contains("example.test"))
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun responseClient(
        body: ResponseBody,
        code: Int = 200,
        call: AtomicReference<Call> = AtomicReference(),
        beforeResponse: () -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callStart(started: Call) { call.set(started) }
        })
        .addInterceptor { chain ->
            beforeResponse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Response").body(body).build()
        }.build()

    private fun trackedBody(text: String, beforeRead: () -> Unit = {}): TrackedBody = TrackedBody(text, beforeRead)

    private class TrackedBody(text: String, beforeRead: () -> Unit) : ResponseBody() {
        val closed = CountDownLatch(1)
        private val input = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                return super.read(sink, byteCount)
            }
            override fun close() {
                super.close()
                closed.countDown()
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = input
    }
}
