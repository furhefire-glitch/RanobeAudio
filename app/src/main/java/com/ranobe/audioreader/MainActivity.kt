package com.ranobe.audioreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ranobe.audioreader.data.settings.AppSettings
import com.ranobe.audioreader.ui.navigation.AppNavGraph
import com.ranobe.audioreader.ui.theme.RanobeAudioTheme
import com.ranobe.audioreader.util.BackgroundPermissionHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requestedChapter by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestedChapter = intent.getLongExtra("chapterId", -1).takeIf { it > 0 }
        val container = (application as RanobeAudioApp).container
        setContent {
            val settings by container.settingsRepository.flow.collectAsStateWithLifecycle<AppSettings?>(null)
            val scope = rememberCoroutineScope()
            RanobeAudioTheme(darkTheme = settings?.darkTheme ?: true) {
                AppNavGraph(container, requestedChapter) { requestedChapter = null }
                if (settings?.notificationsPrompted == false) {
                    fun finishPrompt(request: Boolean) {
                        scope.launch {
                            container.settingsRepository.update { it.copy(notificationsPrompted = true) }
                            if (request) BackgroundPermissionHelper.requestNotifications(this@MainActivity)
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { finishPrompt(false) },
                        title = { Text("Фоновая озвучка") },
                        text = { Text("Уведомления показывают прогресс озвучки и управление плеером. Ограничения батареи и автозапуск можно настроить в разделе «Настройки».") },
                        confirmButton = { TextButton(onClick = { finishPrompt(true) }) { Text("Разрешить уведомления") } },
                        dismissButton = { TextButton(onClick = { finishPrompt(false) }) { Text("Позже") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedChapter = intent.getLongExtra("chapterId", -1).takeIf { it > 0 }
    }
}
