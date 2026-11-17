package com.ranobe.audioreader.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ranobe.audioreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class StorageSnapshot(
    val audioBytes: Long,
    val textBytes: Long,
    val modelBytes: Long,
    val cacheBytes: Long,
    val freeBytes: Long,
)

private enum class StorageAction(val title: String, val explanation: String) {
    LISTENED(
        "Удалить прослушанное аудио",
        "Будет удалено только аудио прослушанных глав. Текст глав, модели и непрослушанное аудио останутся. Для повторного прослушивания удалённые записи потребуется озвучить заново.",
    ),
    ALL_AUDIO(
        "Удалить всё аудио",
        "Будут удалены все сохранённые аудиозаписи, включая непрослушанные главы. Отменить удаление нельзя. Текст глав и модели останутся. Для прослушивания понадобится новая озвучка.",
    ),
    CACHE(
        "Очистить кэш",
        "Будет очищен кэш обложек. Текст глав, готовое аудио и установленные модели останутся. Обложки будут загружены заново при открытии книг.",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var snapshot by remember(container) { mutableStateOf<StorageSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf("Подсчитываем занятое место…") }
    var snapshotError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<StorageAction?>(null) }
    var cleanupHours by remember { mutableStateOf<Int?>(null) }
    var settingsError by remember { mutableStateOf(false) }
    var settingsRetry by remember { mutableIntStateOf(0) }

    fun refresh(action: StorageAction? = null) {
        if (busy) return
        busy = true
        snapshotError = null
        actionError = null
        progressLabel = if (action == null) "Подсчитываем занятое место…" else "Выполняем очистку…"
        scope.launch {
            var freedBytes: Long? = null
            var failedAction = false
            try {
                if (action != null) {
                    try {
                        freedBytes = withContext(Dispatchers.IO) {
                            when (action) {
                                StorageAction.LISTENED -> container.storageRepository.clearListenedAudio()
                                StorageAction.ALL_AUDIO -> container.storageRepository.clearAllAudio()
                                StorageAction.CACHE -> container.storageRepository.clearCache()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedAction = true
                        actionError = "Не удалось завершить очистку. Часть данных могла быть удалена. Проверьте объём и при необходимости повторите действие."
                    }
                }
                // Refresh even after a failed action: the backend may have deleted some files.
                progressLabel = "Обновляем сведения о хранилище…"
                try {
                    snapshot = withContext(Dispatchers.IO) {
                        val stats = container.storageRepository.snapshot()
                        StorageSnapshot(stats.audioBytes, stats.textBytes, stats.modelBytes, stats.cacheBytes, stats.freeBytes)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snapshotError = "Не удалось получить объём хранилища. Проверьте доступность памяти и повторите обновление."
                }
            } finally {
                busy = false
            }
            val message = when {
                failedAction && snapshotError != null -> "Очистка не завершена. Обновить объём тоже не удалось."
                failedAction -> "Очистка не завершена. Сведения о хранилище обновлены."
                freedBytes != null && snapshotError != null -> "Освобождено ${formatBytes(freedBytes)}. Обновите сведения о хранилище."
                snapshotError != null -> "Не удалось обновить сведения о хранилище."
                freedBytes != null -> "Освобождено ${formatBytes(freedBytes)}."
                else -> null
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(container) { refresh() }
    LaunchedEffect(container, lifecycleOwner, settingsRetry) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            settingsError = false
            try {
                container.settingsRepository.flow.collect {
                    cleanupHours = it.cleanupAfterHours
                    settingsError = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsError = true
                snackbar.showSnackbar("Не удалось прочитать настройку автоочистки.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Хранилище") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Управляйте сохранённым аудио и временными данными. Текст глав и голосовые модели при очистке останутся.")
                    OutlinedButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Обновить сведения")
                    }
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(progressLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (snapshotError != null) item {
                StorageSection("Не удалось обновить сведения") {
                    Text(snapshotError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (snapshot != null) Text("Ниже показаны последние известные значения; они могут быть устаревшими.")
                    TextButton(onClick = { refresh() }, enabled = !busy) { Text("Повторить обновление") }
                }
            }
            if (actionError != null) item {
                StorageSection("Очистка не завершена") {
                    Text(actionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            snapshot?.let { stats ->
                item {
                    StorageSection("Использование памяти") {
                        StorageSize("Аудио глав", stats.audioBytes)
                        StorageSize("Текст глав", stats.textBytes)
                        StorageSize("Голосовые модели", stats.modelBytes)
                        StorageSize("Кэш обложек", stats.cacheBytes)
                        StorageSize("Свободно на устройстве", stats.freeBytes)
                        Text("Объём может измениться во время скачивания или озвучки. 1 КиБ = 1024 байта.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                StorageSection("Очистка аудио") {
                    Text("Прослушанное аудио можно удалить отдельно. Удалённые записи потребуется озвучить заново; текст глав и модели останутся.")
                    OutlinedButton(
                        onClick = { confirmation = StorageAction.LISTENED },
                        enabled = !busy && snapshotError == null && (snapshot?.audioBytes ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Удалить прослушанное аудио") }
                    OutlinedButton(
                        onClick = { confirmation = StorageAction.ALL_AUDIO },
                        enabled = !busy && snapshotError == null && (snapshot?.audioBytes ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Удалить всё аудио", color = MaterialTheme.colorScheme.error) }
                    if (snapshot?.audioBytes == 0L && !busy && snapshotError == null) Text("Сохранённого аудио нет.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                StorageSection("Кэш обложек") {
                    Text("Очистка временных данных не удаляет текст глав, готовое аудио и установленные модели.")
                    OutlinedButton(
                        onClick = { confirmation = StorageAction.CACHE },
                        enabled = !busy && snapshotError == null && (snapshot?.cacheBytes ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Очистить кэш") }
                    if (snapshot?.cacheBytes == 0L && !busy && snapshotError == null) Text("Кэш пуст.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                StorageSection("Автоочистка") {
                    when {
                        settingsError -> {
                            Text("Текущая настройка недоступна.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { settingsRetry++ }) { Text("Повторить загрузку настройки") }
                        }
                        cleanupHours == null -> Text("Загружаем настройку…")
                        cleanupHours == 0 -> Text("Автоочистка прослушанного аудио выключена.")
                        else -> Text("Прослушанное аудио автоматически удаляется через $cleanupHours ч. Текст глав и модели сохраняются.")
                    }
                    Text("Изменить срок или выключить автоочистку можно на экране «Настройки», в разделе «Автоочистка».", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("${action.title}?") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(action.explanation)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { confirmation = null; refresh(action) }) {
                    Text(if (action == StorageAction.ALL_AUDIO) "Удалить всё аудио" else "Подтвердить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StorageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun StorageSize(label: String, bytes: Long) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formatBytes(bytes), style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024) return "$safe Б"
    val units = listOf("Б", "КиБ", "МиБ", "ГиБ", "ТиБ", "ПиБ", "ЭиБ")
    var value = safe.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.forLanguageTag("ru-RU"), "%.1f %s", value, units[index])
}
