package com.ranobe.audioreader.data.remote

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

class HttpClientProvider(context: Context) {
    private val authStore = AuthStore(context)

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .addNetworkInterceptor(WebViewUserAgentInterceptor { authStore.webViewUserAgent })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        CookieManager.getInstance().setAcceptCookie(true)
    }
}

internal class WebViewUserAgentInterceptor(private val userAgent: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val agent = userAgent()?.takeIf { it.isNotBlank() && it.all { char -> char in ' '..'~' } }
        val useWebViewAgent = request.url.isHttps && request.url.host in SOURCE_HOSTS
        return chain.proceed(
            if (useWebViewAgent && agent != null) request.newBuilder().header("User-Agent", agent).build()
            else request,
        )
    }

    private companion object {
        val SOURCE_HOSTS = setOf("ranobelib.me", "api.cdnlibs.org", "tl.rulate.ru", "ranobehub.org")
    }
}

class WebViewCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        cookies.forEach { cookieManager.setCookie(target, it.toString()) }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { part ->
            val pair = part.trim().split("=", limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) {
                runCatching {
                    Cookie.Builder().name(pair[0].trim()).value(pair[1].trim()).domain(url.host).build()
                }.getOrNull()
            } else null
        }
    }
}
