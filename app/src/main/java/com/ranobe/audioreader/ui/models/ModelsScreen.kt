package com.ranobe.audioreader.ui.models

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.audio.CatalogEntry
import com.ranobe.audioreader.domain.audio.InstalledModel
import com.ranobe.audioreader.domain.audio.ModelDownloadWorker
import com.ranobe.audioreader.domain.audio.ModelPackArchive
import com.ranobe.audioreader.domain.audio.ModelPreview
import com.ranobe.audioreader.domain.audio.SileroModelManager
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = container.sileroModelManager
    val models by manager.models.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val preview = remember(container, context) { ModelPreview(context, manager, container.stressDictionary) }
    val previewState by preview.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("Здравствуйте! Это проверка выбранного голоса.") }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val importProgress = remember { MutableStateFlow(0L to -1L) }
    val progress by importProgress.collectAsStateWithLifecycle()

    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Не удалось выполнить действие"
            }
        }
    }

    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            preview.stop()
            importing = true
            message = null
            importProgress.value = 0L to -1L
            importJob = scope.launch {
                try {
                    val file = importDocument(context, uri, manager) { done, total -> importProgress.value = done to total }
                    message = "Пак ${file.name} установлен"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message ?: "Не удалось импортировать ZIP"
                } finally {
                    importing = false
                    importJob = null
                }
            }
        }
    }

    LaunchedEffect(manager) {
        try {
            manager.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Не удалось прочитать модели"
        }
    }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) preview.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            importJob?.cancel()
            preview.close()
        }
    }
    LaunchedEffect(settings?.modelId, settings?.speaker) { preview.stop() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Модели и голоса") }, navigationIcon = {
            TextButton(onClick = { preview.stop(); onBack() }) { Text("Назад") }
        })
    }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Импортируйте только доверенные паки RuVoice. Не произвольные .pt/.onnx/GGUF",
                    color = MaterialTheme.colorScheme.error)
                Text("Поддерживаются русские паки формата 2–3. Установленный пак не перезаписывается.")
            }
            item {
                val current = settings
                if (current == null) {
                    Text("Загрузка настроек…")
                } else {
                    val selected = models.firstOrNull { it.id == current.modelId }
                    Text("Выбрано: ${current.modelId} / ${current.speaker}", style = MaterialTheme.typography.titleMedium)
                    if (selected == null) {
                        Text("Пак недоступен. Скачайте его из каталога или импортируйте ZIP.", color = MaterialTheme.colorScheme.error)
                    } else if (current.speaker !in selected.speakers) {
                        Text("Голос недоступен. Выберите голос из списка пака.", color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        Checkbox(checked = current.wifiOnly, onCheckedChange = { enabled ->
                            action { container.settingsRepository.update { it.copy(wifiOnly = enabled) } }
                        })
                        Text("Загружать только по Wi-Fi (безлимитная сеть)", modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("Ограничение применяется к новым загрузкам.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Button(enabled = !importing && !deleting, onClick = {
                    openZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text("Импортировать ZIP") }
                if (importing) {
                    Text("Копирование и проверка ZIP…")
                    ByteProgress(progress.first, progress.second)
                    TextButton(onClick = { importJob?.cancel() }) { Text("Отменить импорт") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (deleting) {
                    Text("Удаление: ожидание завершения работы с аудио…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            item { Text("Каталог", style = MaterialTheme.typography.titleLarge) }
            items(manager.catalog, key = { "catalog-${it.id}" }) { entry ->
                CatalogModelCard(entry, models.any { it.id == entry.id }, settings != null, context,
                    onDownload = {
                        action { ModelDownloadWorker.enqueue(context, entry.id) }
                    },
                    onDelete = { pendingDelete = entry.id },
                )
            }
            item { Text("Установленные паки", style = MaterialTheme.typography.titleLarge) }
            if (models.isEmpty()) item { Text("Нет проверенных паков. Скачайте или импортируйте модель.") }
            items(models, key = { "installed-${it.id}" }) { model ->
                InstalledModelCard(model, settings?.modelId, settings?.speaker,
                    enabled = settings != null && !deleting,
                    onChoose = { speaker ->
                        preview.stop()
                        action { container.settingsRepository.update { it.copy(modelId = model.id, speaker = speaker) } }
                    },
                    onDelete = { preview.stop(); pendingDelete = model.id },
                )
            }
            item {
                val current = settings
                val canPreview = current != null && models.any { it.id == current.modelId && current.speaker in it.speakers }
                Text("Проверка голоса", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = text, onValueChange = { text = it.take(500) },
                    label = { Text("Тестовый текст") }, supportingText = { Text("${text.length}/500") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = canPreview && text.isNotBlank() && !previewState.busy && !previewState.playing && !deleting,
                        onClick = {
                            current?.let { preview.play(it.modelId, it.speaker, text, it.sampleRate, it.bitrate) }
                        }) { Text("Проверить голос") }
                    if (previewState.busy || previewState.playing) {
                        TextButton(onClick = preview::stop) { Text("Остановить") }
                    }
                }
                if (previewState.busy) {
                    Text("Подготовка голоса…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (previewState.playing) Text("Воспроизведение теста")
                previewState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { Text("Лицензия модели может ограничивать использование аудио.", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Удалить пак $id?") },
            text = { Text("Файлы модели будут удалены. Выбранные модель и голос в настройках не изменятся.") },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Оставить") } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    pendingDelete = null
                    preview.stop()
                    deleting = true
                    action {
                        try {
                            manager.delete(id)
                            message = "Пак $id удалён"
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text("Удалить") }
            },
        )
    }
}

@Composable
private fun CatalogModelCard(
    entry: CatalogEntry,
    installed: Boolean,
    enabled: Boolean,
    context: Context,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val flow = remember(context, entry.id) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.uniqueName(entry.id))
    }
    val jobs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = jobs.firstOrNull { !it.state.isFinished }
    val failed = jobs.firstOrNull { it.state == WorkInfo.State.FAILED }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(entry.description)
            Text("Лицензия: ${entry.license}")
            Text("Источник загрузки: ${entry.url}", style = MaterialTheme.typography.bodySmall)
            if (active != null) {
                Text(when (active.state) {
                    WorkInfo.State.RUNNING -> "Загрузка и проверка…"
                    WorkInfo.State.BLOCKED -> "Ожидание запуска"
                    else -> "Ожидание сети и запуска"
                })
                ByteProgress(active.progress.getLong(ModelDownloadWorker.KEY_DONE, 0),
                    active.progress.getLong(ModelDownloadWorker.KEY_TOTAL, -1))
                TextButton(onClick = { ModelDownloadWorker.cancel(context, entry.id) }) { Text("Отменить загрузку") }
            } else {
                if (!installed) {
                    failed?.outputData?.getString(ModelDownloadWorker.KEY_ERROR)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (jobs.any { it.state == WorkInfo.State.CANCELLED }) Text("Загрузка отменена")
                }
                Button(enabled = enabled && !installed, onClick = onDownload) {
                    Text(if (installed) "Установлен" else "Скачать")
                }
                if (!installed) TextButton(onClick = onDelete) { Text("Удалить файлы прежнего пака…") }
            }
        }
    }
}

@Composable
private fun InstalledModelCard(
    model: InstalledModel,
    selectedId: String?,
    selectedSpeaker: String?,
    enabled: Boolean,
    onChoose: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text("${model.id} · ${bytes(model.sizeBytes)} · ${model.speakers.size} голосов")
            Text("Лицензия: ${model.license ?: "не указана в pack.json"}")
            Text("Источник: ${model.source ?: "не указан в pack.json"}", style = MaterialTheme.typography.bodySmall)
            Column {
                Button(enabled = enabled, onClick = { expanded = true }) {
                    Text(if (selectedId == model.id && selectedSpeaker in model.speakers) "Голос: $selectedSpeaker" else "Выбрать пак и голос")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    model.speakers.keys.forEach { speaker ->
                        DropdownMenuItem(text = { Text(speaker) }, onClick = { expanded = false; onChoose(speaker) })
                    }
                }
            }
            TextButton(enabled = enabled, onClick = onDelete) { Text("Удалить пак…") }
        }
    }
}

@Composable
private fun ByteProgress(done: Long, total: Long) {
    if (total > 0) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${bytes(done)} / ${bytes(total)}")
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(if (done > 0) "${bytes(done)} · размер неизвестен" else "Подготовка…")
    }
}

private fun bytes(value: Long): String = String.format(Locale.ROOT, "%.1f МиБ", value / (1024.0 * 1024.0))

private suspend fun importDocument(
    context: Context,
    uri: Uri,
    manager: SileroModelManager,
    progress: (Long, Long) -> Unit,
): File {
    var temporary: File? = null
    try {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val file = File.createTempFile("model-import-", ".zip", context.cacheDir)
            temporary = file
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    val limit = ModelPackArchive.Limits().zipBytes
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > limit - done) throw IOException("ZIP превышает 1 ГиБ")
                        output.write(buffer, 0, count)
                        done += count
                        progress(done, -1)
                    }
                }
            } ?: throw IOException("Не удалось открыть документ")
            currentCoroutineContext().ensureActive()
            file.inputStream().use { manager.importZip(it, progress) }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
    }
}
