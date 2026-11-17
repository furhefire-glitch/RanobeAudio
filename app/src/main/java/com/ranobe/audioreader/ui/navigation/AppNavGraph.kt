package com.ranobe.audioreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ranobe.audioreader.ui.library.LibraryScreen
import com.ranobe.audioreader.ui.library.NovelScreen
import com.ranobe.audioreader.ui.browse.BrowseScreen
import com.ranobe.audioreader.ui.models.ModelsScreen
import com.ranobe.audioreader.ui.settings.SettingsScreen
import com.ranobe.audioreader.ui.dictionary.DictionaryScreen
import com.ranobe.audioreader.ui.storage.StorageScreen
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.playback.AudioPlayerController
import com.ranobe.audioreader.ui.auth.AuthScreen
import com.ranobe.audioreader.ui.reader.ReaderScreen
import com.ranobe.audioreader.ui.reader.SavedChaptersScreen

@Composable
fun AppNavGraph(container: AppContainer, requestedChapter: Long? = null, onRequestHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext
    val player = remember(context) { AudioPlayerController(context) }
    DisposableEffect(player) { onDispose { player.disconnect() } }
    fun openReader(id: Long) {
        navController.navigate("reader/$id") {
            if (navController.currentDestination?.route == "reader/{chapterId}") {
                popUpTo("reader/{chapterId}") { inclusive = true }
            }
            launchSingleTop = true
        }
    }
    LaunchedEffect(requestedChapter) {
        requestedChapter?.let { openReader(it); onRequestHandled() }
    }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            LibraryScreen(container,
                onBrowse = { navController.navigate("browse") },
                onOpenNovel = { navController.navigate("novel/$it") },
                onModels = { navController.navigate("models") },
                onSettings = { navController.navigate("settings") },
                onDictionary = { navController.navigate("dictionary") },
                onStorage = { navController.navigate("storage") },
                onSaved = { navController.navigate("saved") })
        }
        composable("browse") {
            BrowseScreen(container, onBack = { navController.popBackStack() },
                onOpenNovel = { navController.navigate("novel/$it") }, onOpenAuth = { navController.navigate("auth/$it") })
        }
        composable("novel/{novelId}", arguments = listOf(navArgument("novelId") { type = NavType.LongType })) { entry ->
            NovelScreen(container, entry.arguments!!.getLong("novelId"), onBack = { navController.popBackStack() },
                onRead = ::openReader, onModels = { navController.navigate("models") }, onOpenAuth = { navController.navigate("auth/$it") })
        }
        composable("models") { ModelsScreen(container, onBack = { navController.popBackStack() }) }
        composable("settings") {
            SettingsScreen(container, onBack = { navController.popBackStack() },
                onOpenAuth = { navController.navigate("auth/$it") }, onModels = { navController.navigate("models") })
        }
        composable("dictionary") { DictionaryScreen(container, onBack = { navController.popBackStack() }) }
        composable("storage") { StorageScreen(container, onBack = { navController.popBackStack() }) }
        composable("auth/{sourceId}") { entry ->
            val sourceId = entry.arguments?.getString("sourceId").orEmpty()
            AuthScreen(container, sourceId, onDone = { navController.popBackStack() })
        }
        composable("saved") {
            SavedChaptersScreen(container, onBack = { navController.popBackStack() }, onOpenChapter = ::openReader)
        }
        composable("reader/{chapterId}", arguments = listOf(navArgument("chapterId") { type = NavType.LongType })) { entry ->
            ReaderScreen(container, player, entry.arguments!!.getLong("chapterId"), onBack = { navController.popBackStack() }, onOpenChapter = ::openReader)
        }
    }
}
