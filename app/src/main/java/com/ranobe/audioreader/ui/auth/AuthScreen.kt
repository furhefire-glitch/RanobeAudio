package com.ranobe.audioreader.ui.auth

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ranobe.audioreader.data.remote.AuthStore
import com.ranobe.audioreader.data.remote.RANOBELIB_AUTH_ORIGIN
import com.ranobe.audioreader.data.remote.SourceJson
import com.ranobe.audioreader.data.remote.isSafeAuthNavigation
import com.ranobe.audioreader.data.remote.isTrustedRanobeLibOrigin
import com.ranobe.audioreader.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(container: AppContainer, sourceId: String, onDone: () -> Unit) {
    val source = container.sourceById(sourceId)
    val loginUrl = when (sourceId) {
        "ranobelib" -> "$RANOBELIB_AUTH_ORIGIN/ru/login"
        "rulate" -> "https://tl.rulate.ru/"
        else -> source?.baseUrl.orEmpty()
    }
    var webView by remember(sourceId) { mutableStateOf<WebView?>(null) }
    var finishing by remember(sourceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnDone by rememberUpdatedState(onDone)
    val finish: () -> Unit = {
        if (!finishing) {
            finishing = true
            scope.launch {
                try {
                    withTimeoutOrNull(1500) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val view = webView
                            val client = view?.webViewClient as? AuthWebViewClient
                            if (view == null || client == null) {
                                continuation.resume(Unit)
                            } else {
                                client.extractAuth(view) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                        }
                    }
                } finally {
                    CookieManager.getInstance().flush()
                }
                currentOnDone()
            }
        }
    }
    BackHandler(onBack = finish)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Вход: ${source?.name ?: sourceId}") },
                navigationIcon = {
                    IconButton(onClick = finish, enabled = !finishing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = { TextButton(onClick = finish, enabled = !finishing) { Text("Готово") } },
            )
        },
    ) { padding ->
        key(sourceId) {
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        container.authStore.webViewUserAgent = settings.userAgentString
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = AuthWebViewClient(container.authStore, sourceId == "ranobelib")
                        webView = this
                        if (isSafeAuthNavigation(loginUrl)) loadUrl(loginUrl)
                    }
                },
                onRelease = { view ->
                    (view.webViewClient as? AuthWebViewClient)?.release()
                    view.webViewClient = WebViewClient()
                    view.stopLoading()
                    CookieManager.getInstance().flush()
                    if (webView === view) webView = null
                    view.removeAllViews()
                    view.destroy()
                },
            )
        }
    }
}

private class AuthWebViewClient(
    private val store: AuthStore,
    private val captureRanobeLib: Boolean,
) : WebViewClient() {
    private var released = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isSafeAuthNavigation(request.url.toString())

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        !isSafeAuthNavigation(url)

    override fun onPageFinished(view: WebView, url: String) {
        if (released) return
        CookieManager.getInstance().flush()
        if (isTrustedRanobeLibOrigin(url)) extractAuth(view)
    }

    fun extractAuth(view: WebView, onComplete: () -> Unit = {}) {
        if (released || !captureRanobeLib || !isTrustedRanobeLibOrigin(view.url)) {
            onComplete()
            return
        }
        // Navigation can race evaluateJavascript; check the executing document's origin too.
        view.evaluateJavascript(
            """(function(){try{
                if(window.location.origin !== '$RANOBELIB_AUTH_ORIGIN') return null;
                return window.localStorage.getItem('auth');
            }catch(e){return null;}})()""".trimIndent(),
        ) { value ->
            if (!released && isTrustedRanobeLibOrigin(view.url)) saveRanobeLibAuth(store, value)
            onComplete()
        }
    }

    fun release() {
        released = true
    }
}

private fun saveRanobeLibAuth(store: AuthStore, jsValue: String?) {
    if (jsValue.isNullOrEmpty() || jsValue == "null") return
    runCatching {
        val raw = SourceJson.parseToJsonElement(jsValue).jsonPrimitive.content
        val obj = SourceJson.parseToJsonElement(raw).jsonObject
        val token = obj["token"]?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull
        val userId = obj["auth"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        if (!token.isNullOrBlank() && token.all { it in '!'..'~' }) {
            store.ranobeLibToken = token
            store.ranobeLibUserId = userId ?: 0L
        }
    }
}
