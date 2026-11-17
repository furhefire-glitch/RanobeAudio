package com.ranobe.audioreader.di

import android.content.Context
import androidx.room.Room
import com.ranobe.audioreader.data.local.AppDatabase
import com.ranobe.audioreader.data.local.storage.AudioFileManager
import com.ranobe.audioreader.data.remote.AuthStore
import com.ranobe.audioreader.data.remote.HttpClientProvider
import com.ranobe.audioreader.data.remote.NovelSource
import com.ranobe.audioreader.data.remote.sources.RanobeLibSource
import com.ranobe.audioreader.data.remote.sources.RanobehubSource
import com.ranobe.audioreader.data.remote.sources.RulateSource
import com.ranobe.audioreader.domain.audio.SileroModelManager
import com.ranobe.audioreader.domain.text.StressDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    val novelDao = database.novelDao()
    val chapterDao = database.chapterDao()
    val chunkTimingDao = database.chunkTimingDao()
    val dictionaryDao = database.dictionaryDao()

    val audioFileManager = AudioFileManager(context)

    val authStore = AuthStore(context)
    val httpClient = HttpClientProvider(context).client

    val sources: List<NovelSource> = listOf(
        RanobeLibSource(httpClient, authStore),
        RanobehubSource(httpClient),
        RulateSource(httpClient),
    )

    fun sourceById(id: String): NovelSource? = sources.firstOrNull { it.id == id }

    val settingsRepository = com.ranobe.audioreader.data.settings.SettingsRepository(context)
    val sileroModelManager = SileroModelManager(context, httpClient)
    val stressDictionary = StressDictionary(dictionaryDao)
    val libraryRepository = com.ranobe.audioreader.data.repository.LibraryRepository(context, this)
    val storageRepository = com.ranobe.audioreader.domain.storage.StorageRepository(context, this)
}
