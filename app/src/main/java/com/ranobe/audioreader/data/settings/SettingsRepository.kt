package com.ranobe.audioreader.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerSettings by preferencesDataStore("reader_settings")

data class AppSettings(
    val modelId: String = "ru",
    val speaker: String = "xenia",
    val sampleRate: Int = 48000,
    val bitrate: Int = 48000,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val cleanupAfterHours: Int = 0,
    val darkTheme: Boolean = true,
    val textSize: Int = 19,
    val autoScroll: Boolean = true,
    val rewindSeconds: Int = 5,
    val notificationsPrompted: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.readerSettings
    val flow: Flow<AppSettings> = store.data.map(::decode)
    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val value = transform(decode(prefs))
            require(Regex("[a-z0-9_]{1,40}").matches(value.modelId))
            require(value.speaker.isNotBlank())
            require(value.sampleRate in listOf(24000, 48000))
            require(value.bitrate in listOf(32000, 48000, 64000, 96000))
            require(value.cleanupAfterHours in 0..8760 && value.textSize in 14..32 && value.rewindSeconds in listOf(0, 5))
            prefs[MODEL] = value.modelId; prefs[SPEAKER] = value.speaker
            prefs[RATE] = value.sampleRate; prefs[BITRATE] = value.bitrate
            prefs[WIFI] = value.wifiOnly; prefs[CHARGING] = value.chargingOnly
            prefs[CLEANUP] = value.cleanupAfterHours; prefs[DARK] = value.darkTheme
            prefs[TEXT_SIZE] = value.textSize; prefs[SCROLL] = value.autoScroll
            prefs[REWIND] = value.rewindSeconds; prefs[PROMPTED] = value.notificationsPrompted
        }
    }

    private fun decode(p: Preferences) = AppSettings(
        modelId = p[MODEL] ?: "ru", speaker = p[SPEAKER] ?: "xenia",
        sampleRate = p[RATE] ?: 48000, bitrate = p[BITRATE] ?: 48000,
        wifiOnly = p[WIFI] ?: false, chargingOnly = p[CHARGING] ?: false,
        cleanupAfterHours = p[CLEANUP] ?: 0, darkTheme = p[DARK] ?: true,
        textSize = p[TEXT_SIZE] ?: 19, autoScroll = p[SCROLL] ?: true,
        rewindSeconds = p[REWIND] ?: 5, notificationsPrompted = p[PROMPTED] ?: false,
    )

    private companion object {
        val MODEL = stringPreferencesKey("model_id")
        val SPEAKER = stringPreferencesKey("speaker")
        val RATE = intPreferencesKey("sample_rate")
        val BITRATE = intPreferencesKey("bitrate")
        val WIFI = booleanPreferencesKey("wifi_only")
        val CHARGING = booleanPreferencesKey("charging_only")
        val CLEANUP = intPreferencesKey("cleanup_hours")
        val DARK = booleanPreferencesKey("dark_theme")
        val TEXT_SIZE = intPreferencesKey("text_size")
        val SCROLL = booleanPreferencesKey("auto_scroll")
        val REWIND = intPreferencesKey("rewind_seconds")
        val PROMPTED = booleanPreferencesKey("notifications_prompted")
    }
}
