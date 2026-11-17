package com.ranobe.audioreader.data.remote

import android.content.Context
import java.net.URI

class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    var ranobeLibToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var ranobeLibUserId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var webViewUserAgent: String?
        get() = prefs.getString(KEY_USER_AGENT, null)
        set(value) {
            if (value == null || (value.isNotBlank() && value.all { it in ' '..'~' })) {
                prefs.edit().putString(KEY_USER_AGENT, value).apply()
            }
        }

    fun clearRanobeLib() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_ID).apply()
    }

    private companion object {
        const val KEY_TOKEN = "ranobelib_token"
        const val KEY_USER_ID = "ranobelib_user_id"
        const val KEY_USER_AGENT = "webview_user_agent"
    }
}

internal const val RANOBELIB_AUTH_ORIGIN = "https://ranobelib.me"

internal fun isSafeAuthNavigation(url: String?): Boolean {
    val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port in 1..65535)
}

internal fun isTrustedRanobeLibOrigin(url: String?): Boolean {
    if (!isSafeAuthNavigation(url)) return false
    val uri = URI(url!!)
    return uri.host.equals("ranobelib.me", ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443)
}
