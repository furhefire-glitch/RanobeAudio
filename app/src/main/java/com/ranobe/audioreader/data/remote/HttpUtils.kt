package com.ranobe.audioreader.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resumeWithException

val SourceJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

inline fun <reified T> String.parseJson(): T = SourceJson.decodeFromString(this)

fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

suspend fun OkHttpClient.fetchString(url: String, headers: Map<String, String> = emptyMap()): String =
    suspendCancellableCoroutine { continuation ->
        val request = try {
            Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
        } catch (_: IllegalArgumentException) {
            continuation.resumeWithException(IOException("Некорректный HTTP-запрос"))
            return@suspendCancellableCoroutine
        }
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(IOException("Ошибка сети при загрузке"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result: Result<String> = try {
                    response.use {
                        if (!continuation.isActive) return
                        when {
                            !it.isSuccessful -> Result.failure(IOException("HTTP ${it.code}"))
                            it.body == null -> Result.failure(IOException("Пустой ответ сервера"))
                            else -> Result.success(it.body!!.string())
                        }
                    }
                } catch (_: IOException) {
                    Result.failure(IOException("Не удалось прочитать ответ сервера"))
                }
                continuation.resumeWith(result)
            }
        })
    }
