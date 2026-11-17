package com.ranobe.audioreader.data.remote

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthPolicyTest {
    @Test fun extractsOnlyFromVerifiedRanobeLibOrigin() {
        listOf("https://ranobelib.me/ru/login", "https://ranobelib.me:443/ru/book/1?spa=1#state",
            "https://RANOBELIB.ME/").forEach { assertTrue(isTrustedRanobeLibOrigin(it)) }
        listOf(null, "", "http://ranobelib.me/", "https://ranobelib.me:444/",
            "https://ranobelib.me.evil.example/", "https://evil.example/ranobelib.me",
            "https://sub.ranobelib.me/", "https://ranobelib.me@evil.example/",
            "https://evil@ranobelib.me/", "https://api.cdnlibs.org/", "https://ranobelib.me./",
            "https://ranobelib.me\\@evil.example/").forEach { assertFalse(isTrustedRanobeLibOrigin(it)) }
    }

    @Test fun allowsExternalHttpsOAuthWithoutTrustingItsStorage() {
        val url = "https://oauth.example/authorize?redirect_uri=https%3A%2F%2Franobelib.me%2Fcallback"
        assertTrue(isSafeAuthNavigation(url))
        assertFalse(isTrustedRanobeLibOrigin(url))
    }

    @Test fun rejectsLocalAndExecutableNavigationSchemes() {
        listOf("file:///sdcard/page.html", "content://provider/file", "javascript:alert(1)",
            "data:text/html,test", "intent://login", "about:blank", "http://oauth.example/",
            "https:///missing-host", "https://oauth.example:70000/", "https://oauth.example/\nnext"
        ).forEach { assertFalse(isSafeAuthNavigation(it)) }
    }

    @Test fun webViewAgentOverridesSourceFallbackAndUpdatesWithoutRebuildingClient() {
        var agent: String? = null
        val observed = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(WebViewUserAgentInterceptor { agent })
            .addInterceptor { chain ->
                observed += chain.request().header("User-Agent")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        fun request(url: String) {
            client.newCall(Request.Builder().url(url).header("User-Agent", "source-fallback").build())
                .execute().close()
        }
        request("https://tl.rulate.ru/api3/book")
        agent = "WebView-Test/1"
        listOf("tl.rulate.ru", "ranobelib.me", "api.cdnlibs.org", "ranobehub.org").forEach {
            request("https://$it/")
        }
        agent = "WebView-Test/2"
        request("https://tl.rulate.ru/")
        request("https://download.example/model")
        request("http://tl.rulate.ru/")
        agent = "invalid\r\nheader"
        request("https://tl.rulate.ru/")
        assertEquals(listOf("source-fallback", "WebView-Test/1", "WebView-Test/1", "WebView-Test/1",
            "WebView-Test/1", "WebView-Test/2", "source-fallback", "source-fallback", "source-fallback"), observed)
    }
}
