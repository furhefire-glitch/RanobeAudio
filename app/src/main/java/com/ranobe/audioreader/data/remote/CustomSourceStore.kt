package com.ranobe.audioreader.data.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@Serializable
data class CustomSourceEntry(val id: String, val name: String, val baseUrl: String)

class CustomSourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_sources", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val entries: StateFlow<List<CustomSourceEntry>> = mutable.asStateFlow()

    fun add(name: String, baseUrl: String): CustomSourceEntry {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Укажите название источника" }
        val url = normalizeBaseUrl(baseUrl) ?: throw IllegalArgumentException("Некорректная ссылка: нужен адрес сайта вида https://example.com")
        val current = mutable.value
        require(current.none { it.baseUrl == url }) { "Такой сайт уже добавлен" }
        val entry = CustomSourceEntry("custom-${UUID.randomUUID().toString().take(8)}", cleanName, url)
        persist(current + entry)
        return entry
    }

    fun remove(id: String) {
        persist(mutable.value.filterNot { it.id == id })
    }

    private fun read(): List<CustomSourceEntry> = runCatching {
        SourceJson.decodeFromString(ListSerializer(CustomSourceEntry.serializer()), prefs.getString(KEY, "[]").orEmpty())
    }.getOrDefault(emptyList())

    private fun persist(list: List<CustomSourceEntry>) {
        prefs.edit().putString(KEY, SourceJson.encodeToString(ListSerializer(CustomSourceEntry.serializer()), list)).apply()
        mutable.value = list
    }

    companion object {
        private const val KEY = "entries"

        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}
