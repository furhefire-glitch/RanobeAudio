package com.ranobe.audioreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.remote.htmlToText
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.audio.AudioRenderWorker
import com.ranobe.audioreader.domain.playback.AudioPlayerController
import com.ranobe.audioreader.domain.playback.PlaybackState
import com.ranobe.audioreader.domain.text.StressDictionary
import com.ranobe.audioreader.domain.text.TextCleaner
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReaderDocument(
    val loading: Boolean = true,
    val chapter: ChapterEntity? = null,
    val novelTitle: String = "",
    val segments: List<ReaderSegment> = emptyList(),
    val synchronized: Boolean = false,
    val audioExists: Boolean = false,
    val previous: Long? = null,
    val next: Long? = null,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    player: AudioPlayerController,
    chapterId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val playback by player.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.flow.collectAsStateWithLifecycle(com.ranobe.audioreader.data.settings.AppSettings())
    val renderJobs by remember(chapterId) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("render-$chapterId")
    }.collectAsStateWithLifecycle(emptyList())
    val renderJob = renderJobs.lastOrNull()
    LaunchedEffect(renderJob?.id, renderJob?.state) {
        if (renderJob?.state == WorkInfo.State.FAILED) snackbar.showSnackbar(renderJob.outputData.getString("error") ?: "Переозвучка не удалась")
    }
    var document by remember(chapterId) { mutableStateOf(ReaderDocument()) }
    LaunchedEffect(chapterId) {
        try {
            container.chapterDao.observeReader(chapterId)
                .map { it?.copy(chapter = it.chapter.copy(positionMs = 0)) }
                .distinctUntilChanged()
                .collect { record ->
                    document = withContext(Dispatchers.IO) {
                        if (record == null) ReaderDocument(loading = false, error = "Глава не найдена")
                        else {
                            val chapter = record.chapter
                            val novel = container.novelDao.getById(chapter.novelId)
                            val chapters = container.chapterDao.getByNovel(chapter.novelId)
                                .filter { it.scanlator?.trim().orEmpty() == chapter.scanlator?.trim().orEmpty() }
                                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                            val index = chapters.indexOfFirst { it.id == chapterId }
                            val text = chapter.renderedText ?: TextCleaner.clean(htmlToText(chapter.html.orEmpty()))
                            val timings = record.timings.sortedBy { it.chunkIndex }.takeIf { chapter.renderedText != null }.orEmpty()
                            ReaderDocument(
                                loading = false, chapter = chapter, novelTitle = novel?.name.orEmpty(),
                                segments = ReaderText.segments(text, timings),
                                synchronized = ReaderText.validTimings(text, timings),
                                audioExists = chapter.audioPath?.let { File(it).isFile } == true,
                                previous = chapters.getOrNull(index - 1)?.id,
                                next = chapters.getOrNull(index + 1)?.id,
                            )
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            document = ReaderDocument(loading = false, error = e.message ?: "Не удалось открыть главу")
        }
    }
    var controllerReady by remember { mutableStateOf(false) }
    var opened by remember(chapterId) { mutableStateOf(false) }
    LaunchedEffect(player) {
        try {
            player.connect()
            controllerReady = player.state.value.connected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "Плеер недоступен")
        }
    }
    LaunchedEffect(chapterId, document.chapter?.audioPath, controllerReady) {
        opened = false
        if (controllerReady && document.audioExists && document.chapter?.id == chapterId) {
            try {
                player.openChapter(chapterId)
                opened = player.state.value.chapterId == chapterId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось открыть аудио")
            }
        }
    }
    var previousPlayingChapter by remember(chapterId) { mutableStateOf<Long?>(null) }
    var switchingChapter by remember(chapterId) { mutableStateOf(false) }
    LaunchedEffect(playback.chapterId, opened) {
        val current = playback.chapterId
        if (opened && !switchingChapter && previousPlayingChapter == chapterId && current != null && current != chapterId) onOpenChapter(current)
        previousPlayingChapter = current
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable(chapterId, settings.autoScroll) { mutableStateOf(settings.autoScroll) }
    val active = if (opened && playback.chapterId == chapterId) ReaderText.activeIndex(document.segments, playback.positionMs) else -1
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow) {
        if (follow && active >= 0) listState.animateScrollToItem(active)
    }
    var selectedWord by rememberSaveable(chapterId) { mutableStateOf<String?>(null) }
    var accented by rememberSaveable(chapterId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var showTimer by rememberSaveable { mutableStateOf(false) }
    var playerExpanded by rememberSaveable { mutableStateOf(true) }
    val canPlay = opened && playback.connected && playback.chapterId == chapterId && document.audioExists

    fun selectWord(word: String) {
        selectedWord = word
        accented = word
        scope.launch {
            try {
                val existing = container.stressDictionary.snapshot().userDict[word.lowercase(Locale.ROOT)]
                if (selectedWord == word && existing != null) accented = existing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось прочитать словарь")
            }
        }
    }

    fun switchChapter(id: Long) {
        switchingChapter = true
        scope.launch {
            if (playback.isPlaying) {
                try {
                    player.openChapter(id, play = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "У следующей главы нет аудио")
                }
            }
            onOpenChapter(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(document.chapter?.name ?: "Читалка", maxLines = 1); Text(document.novelTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = { FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Следить") }) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            PlayerControls(
                state = playback, enabled = canPlay,
                previous = document.previous?.let { { switchChapter(it) } },
                next = document.next?.let { { switchChapter(it) } },
                onToggle = player::togglePlayPause, onSeek = player::seekTo,
                onSpeed = { showSpeed = true }, onTimer = { showTimer = true },
                expanded = playerExpanded, onToggleExpand = { playerExpanded = !playerExpanded },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (document.loading) CircularProgressIndicator(Modifier.padding(24.dp))
            if (!playback.connected && playback.error != null) {
                TextButton(onClick = {
                    scope.launch {
                        controllerReady = false
                        player.connect()
                        controllerReady = player.state.value.connected
                    }
                }) { Text("Подключить плеер повторно") }
            }
            playback.chapterId?.takeIf { it != chapterId }?.let { current ->
                TextButton(onClick = { onOpenChapter(current) }) { Text("Вернуться к главе в плеере") }
            }
            document.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (!document.loading && document.chapter != null) {
                when {
                    document.chapter?.audioStatus == AudioStatus.RENDERING -> Text("Переозвучка: ${renderJob?.progress?.getInt("done", 0) ?: 0} / ${renderJob?.progress?.getInt("total", 0) ?: 0}. Текущая запись пока не изменена.", Modifier.padding(12.dp))
                    renderJob?.state == WorkInfo.State.ENQUEUED -> Text("Переозвучка в очереди.", Modifier.padding(12.dp))
                    !document.audioExists -> Text("Аудио ещё не скачано или удалено. Доступно чтение текста.", Modifier.padding(12.dp))
                    !document.synchronized -> Text("У этой записи нет сохранённого текста с таймкодами. Для подсветки нужна переозвучка.", Modifier.padding(12.dp))
                }
                if (document.segments.isEmpty()) Text("Текст главы ещё не скачан.", Modifier.padding(16.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(document.segments, key = { _, segment -> segment.startChar }) { index, segment ->
                    var layout by remember(segment.text) { mutableStateOf<TextLayoutResult?>(null) }
                    val seek = {
                        segment.timing?.takeIf { canPlay && it.endMs > it.startMs }?.let { player.seekTo(it.startMs) }
                        Unit
                    }
                    Text(
                        segment.text,
                        onTextLayout = { layout = it },
                        fontSize = settings.textSize.sp,
                        lineHeight = (settings.textSize * 1.5f).sp,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (index == active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics {
                                if (segment.timing != null && canPlay) onClick("Перемотать к фрагменту") { seek(); true }
                                customActions = listOf(CustomAccessibilityAction("Исправить ударение") {
                                    selectedWord = ""; accented = ""; true
                                })
                            }
                            .pointerInput(segment, canPlay) {
                                detectTapGestures(
                                    onTap = { seek() },
                                    onLongPress = { point -> layout?.getOffsetForPosition(point)?.let { ReaderText.wordAt(segment.text, it) }?.let(::selectWord) },
                                )
                            },
                    )
                }
            }
        }
    }
    selectedWord?.let { word ->
        val chapter = document.chapter
        val canRender = chapter?.audioModelId != null && chapter.audioSpeaker != null && chapter.audioStatus != AudioStatus.RENDERING
        val valid = StressDictionary.isStress(if (word.isEmpty()) accented.replace("+", "") else word, accented.trim())
        fun save(render: Boolean) {
            saving = true
            scope.launch {
                try {
                    container.stressDictionary.saveStress(if (word.isEmpty()) accented.replace("+", "") else word, accented)
                    if (render && chapter != null) {
                        if (playback.chapterId == chapterId && playback.isPlaying) player.togglePlayPause()
                        AudioRenderWorker.enqueue(context, chapter.id, chapter.novelId, chapter.audioModelId!!, chapter.audioSpeaker!!,
                            chapter.audioSampleRate ?: 48000, chapter.audioBitrate ?: 48000,
                            wifiOnly = settings.wifiOnly, chargingOnly = settings.chargingOnly)
                    }
                    selectedWord = null
                    snackbar.showSnackbar(if (render) "Ударение сохранено. Переозвучка поставлена в очередь." else "Ударение сохранено для следующей озвучки. Готовое аудио не изменено.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Не удалось сохранить ударение")
                } finally { saving = false }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!saving) selectedWord = null },
            title = { Text("Ударение") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Поставьте + перед ударной гласной: зам+ок. Готовая запись изменится только после переозвучки.")
                    OutlinedTextField(value = accented, onValueChange = { accented = it }, singleLine = true, enabled = !saving, label = { Text(if (word.isEmpty()) "Слово с ударением" else word) }, isError = '+' in accented && !valid)
                    if (canRender) TextButton(onClick = { save(true) }, enabled = valid && !saving) { Text("Сохранить и переозвучить главу") }
                }
            },
            confirmButton = { TextButton(onClick = { save(false) }, enabled = valid && !saving) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { selectedWord = null }, enabled = !saving) { Text("Отмена") } },
        )
    }
    if (showSpeed) AlertDialog(
        onDismissRequest = { showSpeed = false }, title = { Text("Скорость: %.1f×".format(playback.speed)) },
        text = { Slider(value = playback.speed.coerceIn(0.8f, 2.5f), onValueChange = player::setSpeed, valueRange = 0.8f..2.5f, steps = 16) },
        confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("Готово") } },
    )
    if (showTimer) AlertDialog(
        onDismissRequest = { showTimer = false }, title = { Text("Таймер сна") },
        text = { Column { listOf(0, 15, 30, 45, 60, 90, 120).forEach { minutes -> TextButton(onClick = { player.setSleepTimer(minutes); showTimer = false }) { Text(if (minutes == 0) "Выключить" else "$minutes минут") } } } },
        confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Закрыть") } },
    )
}

@Composable
private fun PlayerControls(
    state: PlaybackState,
    enabled: Boolean,
    previous: (() -> Unit)?,
    next: (() -> Unit)?,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1).toFloat()
    LaunchedEffect(state.chapterId) { scrubbing = null }
    Surface(tonalElevation = 4.dp) {
        if (!expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                LinearProgressIndicator(
                    progress = { if (enabled && state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggle, enabled = enabled) {
                        if (state.isBuffering && enabled) CircularProgressIndicator(Modifier.size(24.dp))
                        else Icon(if (state.isPlaying && enabled) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying && enabled) "Пауза" else "Слушать")
                    }
                    Text(
                        if (enabled) "${ReaderText.time(state.positionMs)} / ${ReaderText.time(state.durationMs)}" else "Аудио недоступно",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = onToggleExpand) { Icon(Icons.Default.KeyboardArrowUp, "Развернуть плеер") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onToggleExpand) { Icon(Icons.Default.KeyboardArrowDown, "Свернуть плеер") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Slider(
                    value = (scrubbing ?: state.positionMs.toFloat()).coerceIn(0f, duration),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = { scrubbing?.let { onSeek(it.toLong()) }; scrubbing = null },
                    valueRange = 0f..duration, enabled = enabled && state.durationMs > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (enabled) ReaderText.time((scrubbing ?: state.positionMs.toFloat()).toLong()) else "—", style = MaterialTheme.typography.labelSmall)
                    Text(if (enabled) ReaderText.time(state.durationMs) else "—", style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { previous?.invoke() }, enabled = previous != null) { Icon(Icons.Default.SkipPrevious, "Предыдущая глава") }
                    IconButton(onClick = { onSeek((state.positionMs - 10_000).coerceAtLeast(0)) }, enabled = enabled) { Icon(Icons.Default.Replay10, "Назад 10 секунд") }
                    IconButton(onClick = onToggle, enabled = enabled) {
                        if (state.isBuffering && enabled) CircularProgressIndicator()
                        else Icon(if (state.isPlaying && enabled) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying && enabled) "Пауза" else "Слушать")
                    }
                    IconButton(onClick = { onSeek((state.positionMs + 10_000).coerceAtMost(state.durationMs)) }, enabled = enabled) { Icon(Icons.Default.Forward10, "Вперёд 10 секунд") }
                    IconButton(onClick = { next?.invoke() }, enabled = next != null) { Icon(Icons.Default.SkipNext, "Следующая глава") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onSpeed, enabled = state.connected) { Text("%.1f×".format(state.speed)) }
                    TextButton(onClick = onTimer, enabled = state.connected) { Text(if (state.sleepRemainingMs > 0) "Сон: ${ReaderText.time(state.sleepRemainingMs)}" else "Таймер сна") }
                }
            }
        }
    }
}
