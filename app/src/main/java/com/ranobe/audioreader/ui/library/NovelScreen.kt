package com.ranobe.audioreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import com.ranobe.audioreader.data.local.entity.NovelEntity
import com.ranobe.audioreader.data.remote.htmlToText
import com.ranobe.audioreader.data.repository.DeleteMode
import com.ranobe.audioreader.data.settings.AppSettings
import com.ranobe.audioreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NovelDetails(val novel: NovelEntity?, val chapters: List<ChapterEntity>)
private enum class ChapterFilter(val title: String) { ALL("Все"), DOWNLOADED("Скачанные"), AUDIO("Озвученные"), UNREAD("Непрочитанные") }
private enum class ChapterAction { DOWNLOAD, RENDER, CANCEL, DELETE, READ, UNREAD }
private data class ChapterConfirmation(val action: ChapterAction, val ids: List<Long>)
private val ChapterSelectionSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

@Composable
fun NovelScreen(
    container: AppContainer,
    novelId: Long,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    onModels: () -> Unit,
    onOpenAuth: (String) -> Unit,
) {
    key(novelId) {
        NovelContent(container, novelId, onBack, onRead, onModels, onOpenAuth)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NovelContent(
    container: AppContainer,
    novelId: Long,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    onModels: () -> Unit,
    onOpenAuth: (String) -> Unit,
) {
    val repository = container.libraryRepository
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var retry by remember { mutableIntStateOf(0) }
    val detailsFlow = remember(repository, novelId) {
        combine(repository.observeNovel(novelId), repository.observeChapters(novelId)) { novel, chapters ->
            NovelDetails(novel, chapters)
        }
    }
    val detailsState by observeScreenFlow(detailsFlow, retry)
    val details = (detailsState as? ScreenLoad.Ready)?.data
    val novel = details?.novel
    val chapters = details?.chapters.orEmpty()
    val source = remember(container, novel?.sourceId) { novel?.sourceId?.let(container::sourceById) }
    val workFlow = remember(context, novelId) {
        WorkManager.getInstance(context).getWorkInfosByTagFlow("novel-$novelId")
    }
    val workState by observeScreenFlow(workFlow, retry)
    val work = (workState as? ScreenLoad.Ready)?.data.orEmpty()
    val workByChapter = remember(work) { groupChapterWork(work) }
    val settingsFlow = remember(container) { container.settingsRepository.flow }
    val settingsState by observeScreenFlow(settingsFlow, retry)
    val settings = (settingsState as? ScreenLoad.Ready)?.data
    val modelFlow = remember(container, settings?.modelId) {
        container.sileroModelManager.models.map { models -> models.any { it.id == settings?.modelId } }
    }
    val modelState by observeScreenFlow(modelFlow, retry)
    val modelInstalled = (modelState as? ScreenLoad.Ready)?.data == true
    var chapterQuery by rememberSaveable { mutableStateOf("") }
    var chapterFilter by rememberSaveable { mutableStateOf(ChapterFilter.ALL) }
    var selectedBranch by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by rememberSaveable(stateSaver = ChapterSelectionSaver) { mutableStateOf(emptySet<Long>()) }
    var branchMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var retryOperation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmation by remember { mutableStateOf<ChapterConfirmation?>(null) }
    var deleteBook by remember { mutableStateOf(false) }
    var deleteMode by rememberSaveable { mutableStateOf(DeleteMode.AUDIO) }
    val branches = remember(chapters) { chapters.map { it.branchKey() }.distinct() }
    val effectiveBranch = selectedBranch?.takeIf { it in branches } ?: branches.firstOrNull()
    val visibleChapters = remember(chapters, effectiveBranch, chapterQuery, chapterFilter) {
        chapters.filter { chapter ->
            if (chapter.branchKey() != effectiveBranch) return@filter false
            if (chapterQuery.isNotBlank() && !chapter.name.contains(chapterQuery.trim(), ignoreCase = true)) return@filter false
            when (chapterFilter) {
                ChapterFilter.ALL -> true
                ChapterFilter.DOWNLOADED -> chapter.hasLocalContent()
                ChapterFilter.AUDIO -> chapter.audioPath != null
                ChapterFilter.UNREAD -> !chapter.isRead
            }
        }
    }
    val visibleIds = remember(visibleChapters) { visibleChapters.mapTo(LinkedHashSet()) { it.id } }
    val selected = remember(selectedIds, visibleIds) { selectedIds.intersect(visibleIds) }
    val continueChapter = remember(chapters, novel?.lastReadChapterId, visibleChapters) {
        chapters.firstOrNull { it.id == novel?.lastReadChapterId }
            ?: visibleChapters.firstOrNull { !it.isRead } ?: visibleChapters.firstOrNull()
    }
    val listState = rememberLazyListState()
    var jumpTarget by remember { mutableStateOf<Long?>(null) }
    val chapterListOffset = remember(busy, actionError, detailsState, novel, continueChapter, source, workState, branches, visibleChapters) {
        var count = 0
        if (busy) count++
        if (actionError != null) count++
        when (detailsState) {
            ScreenLoad.Loading, is ScreenLoad.Error -> count++
            is ScreenLoad.Ready -> if (novel == null) count++ else {
                count += 2
                if (continueChapter != null) count++
                if (source?.requiresAuth == true) count++
                count++
                when (workState) {
                    ScreenLoad.Loading, is ScreenLoad.Error -> count++
                    is ScreenLoad.Ready -> Unit
                }
                count += 2
                if (branches.size > 1) count++
                count += 2
            }
        }
        count
    }
    LaunchedEffect(jumpTarget, visibleChapters) {
        val target = jumpTarget ?: return@LaunchedEffect
        val index = visibleChapters.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(chapterListOffset + index)
            jumpTarget = null
        }
    }

    LaunchedEffect(visibleIds, detailsState is ScreenLoad.Ready) {
        if (detailsState is ScreenLoad.Ready) selectedIds = selectedIds.intersect(visibleIds)
    }

    val failedWork = remember(work) { work.filter { it.state == WorkInfo.State.FAILED } }
    val reportedFailures = remember { mutableSetOf<String>() }
    LaunchedEffect(failedWork.map { it.id }) {
        val unseen = failedWork.filter { reportedFailures.add(it.id.toString()) }
        if (unseen.isNotEmpty()) {
            val first = unseen.first()
            val reason = first.outputData.getString("error") ?: "Фоновая задача завершилась с ошибкой"
            snackbar.showSnackbar(if (unseen.size == 1) reason else "$reason · Ещё ошибок: ${unseen.size - 1}. Подробности — в списке глав.")
        }
    }

    fun runOperation(success: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        retryOperation = null
        scope.launch {
            val message = try {
                operation()
                success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.uiMessage().also {
                    actionError = it
                    retryOperation = { runOperation(success, operation) }
                }
            } finally {
                busy = false
            }
            snackbar.showSnackbar(message)
        }
    }

    fun requestAction(action: ChapterAction, ids: Collection<Long>) {
        if (busy || ids.isEmpty()) return
        if (action == ChapterAction.RENDER && (modelState as? ScreenLoad.Ready)?.data == false) {
            onModels()
            return
        }
        val requested = ids.toSet()
        val orderedIds = chapters.asSequence().filter { it.id in requested }.map { it.id }.toList()
        if (orderedIds.isNotEmpty()) confirmation = ChapterConfirmation(action, orderedIds)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected.isNotEmpty() && novel != null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
                    Text("Выбрано глав: ${selected.size}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { TextButton(enabled = !busy, onClick = { requestAction(ChapterAction.DOWNLOAD, selected) }) { Text("Скачать текст") } }
                        item { TextButton(enabled = !busy && settings != null, onClick = { requestAction(ChapterAction.RENDER, selected) }) { Text("Озвучить") } }
                        item { TextButton(enabled = !busy, onClick = { requestAction(ChapterAction.CANCEL, selected) }) { Text("Остановить") } }
                        item { TextButton(enabled = !busy, onClick = { deleteMode = DeleteMode.AUDIO; requestAction(ChapterAction.DELETE, selected) }) { Text("Удалить данные") } }
                        item { TextButton(enabled = !busy, onClick = { selectedIds = emptySet() }) { Text("Снять выбор") } }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(novel?.name ?: "Книга", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Действия с книгой") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Обновить описание и главы") },
                            enabled = novel != null && !busy,
                            onClick = { menu = false; runOperation("Описание и главы обновлены") { repository.refreshNovel(novelId) } },
                        )
                        DropdownMenuItem(text = { Text("Модели и голоса") }, onClick = { menu = false; onModels() })
                        if (source?.requiresAuth == true) DropdownMenuItem(
                            text = { Text("Войти в ${source.name}") },
                            onClick = { menu = false; onOpenAuth(source.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить книгу и локальные данные", color = MaterialTheme.colorScheme.error) },
                            enabled = novel != null && !busy,
                            onClick = { menu = false; deleteBook = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) item(key = "operation") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            actionError?.let { message ->
                item(key = "action-error") {
                    ErrorMessage(
                        message,
                        onRetry = { retryOperation?.invoke() },
                        onLogin = if (source?.requiresAuth == true) ({ onOpenAuth(source.id) }) else null,
                    )
                }
            }
            when (val state = detailsState) {
                ScreenLoad.Loading -> item(key = "loading") { LoadingMessage("Загружаем книгу и главы…") }
                is ScreenLoad.Error -> item(key = "load-error") { ErrorMessage(state.message, onRetry = { retry++ }) }
                is ScreenLoad.Ready -> {
                    if (novel == null) item(key = "missing") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Книга не найдена. Возможно, она уже удалена.")
                            OutlinedButton(onClick = onBack) { Text("Назад") }
                        }
                    } else {
                        item(key = "metadata") { NovelMetadata(novel, source?.name ?: novel.sourceId) }
                        item(key = "library-actions") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        runOperation(if (novel.inLibrary) "Книга убрана из библиотеки. Локальные данные сохранены." else "Книга добавлена в библиотеку") {
                                            repository.setInLibrary(novelId, !novel.inLibrary)
                                        }
                                    },
                                ) { Text(if (novel.inLibrary) "Убрать из библиотеки" else "В библиотеку") }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { runOperation("Описание и главы обновлены") { repository.refreshNovel(novelId) } },
                                ) { Text("Обновить главы") }
                            }
                            if (novel.inLibrary) Text("Убрать из библиотеки — не удалять текст и аудио.", style = MaterialTheme.typography.bodySmall)
                        }
                        continueChapter?.let { chapter ->
                            item(key = "continue") {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = chapter.hasLocalContent() || !busy,
                                    onClick = {
                                        if (chapter.hasLocalContent()) onRead(chapter.id)
                                        else requestAction(ChapterAction.DOWNLOAD, listOf(chapter.id))
                                    },
                                ) {
                                    Text(
                                        "${if (novel.lastReadChapterId == chapter.id) "Продолжить" else "Начать чтение"}: ${chapter.name}",
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (source?.requiresAuth == true) item(key = "auth") {
                            TextButton(onClick = { onOpenAuth(source.id) }) { Text("Войти в ${source.name}") }
                        }
                        item(key = "voice") {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Настройки озвучки", style = MaterialTheme.typography.titleSmall)
                                    when (val state = settingsState) {
                                        ScreenLoad.Loading -> LoadingMessage("Загружаем настройки…")
                                        is ScreenLoad.Error -> ErrorMessage(state.message, onRetry = { retry++ })
                                        is ScreenLoad.Ready -> {
                                            Text("Модель: ${state.data.modelId} · голос: ${state.data.speaker}")
                                            Text("${state.data.sampleRate / 1000} кГц · ${state.data.bitrate / 1000} кбит/с", style = MaterialTheme.typography.bodySmall)
                                            if (state.data.wifiOnly || state.data.chargingOnly) Text(
                                                listOfNotNull(
                                                    "Только Wi-Fi".takeIf { state.data.wifiOnly },
                                                    "Только при зарядке".takeIf { state.data.chargingOnly },
                                                ).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            when (val installed = modelState) {
                                                ScreenLoad.Loading -> LoadingMessage("Проверяем модель…")
                                                is ScreenLoad.Error -> ErrorMessage(installed.message, onRetry = { retry++ })
                                                is ScreenLoad.Ready -> if (!installed.data) Text("Выбранная модель не установлена. Сначала скачайте её.")
                                            }
                                            TextButton(onClick = onModels) { Text(if (modelInstalled) "Модели и голоса" else "Установить модель") }
                                        }
                                    }
                                }
                            }
                        }
                        when (val state = workState) {
                            ScreenLoad.Loading -> item(key = "jobs-loading") { LoadingMessage("Получаем состояние задач…") }
                            is ScreenLoad.Error -> item(key = "jobs-error") { ErrorMessage(state.message, onRetry = { retry++ }) }
                            is ScreenLoad.Ready -> Unit
                        }
                        item(key = "chapter-title") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Главы: ${visibleChapters.size} из ${chapters.size}", style = MaterialTheme.typography.titleLarge)
                                val lastReadId = novel.lastReadChapterId
                                if (lastReadId != null && chapters.any { it.id == lastReadId }) {
                                    TextButton(onClick = {
                                        val chapter = chapters.first { it.id == lastReadId }
                                        selectedBranch = chapter.branchKey()
                                        chapterFilter = ChapterFilter.ALL
                                        chapterQuery = ""
                                        jumpTarget = lastReadId
                                    }) { Text("К прочитанной") }
                                }
                            }
                        }
                        item(key = "chapter-filter-chips") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                ChapterFilter.entries.forEach { filter ->
                                    FilterChip(
                                        selected = chapterFilter == filter,
                                        onClick = { chapterFilter = filter },
                                        label = { Text(filter.title) },
                                    )
                                }
                            }
                        }
                        if (branches.size > 1) item(key = "branch") {
                            Column {
                                Text("Переводчик", style = MaterialTheme.typography.labelLarge)
                                OutlinedButton(onClick = { branchMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(effectiveBranch?.ifBlank { "Без указания переводчика" }.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                                    branches.forEach { branch ->
                                        DropdownMenuItem(
                                            text = { Text(branch.ifBlank { "Без указания переводчика" }) },
                                            onClick = { branchMenu = false; selectedBranch = branch; selectedIds = emptySet() },
                                        )
                                    }
                                }
                                Text("Показана одна ветка перевода. При смене переводчика выбор глав сбрасывается.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item(key = "chapter-filter") {
                            OutlinedTextField(
                                value = chapterQuery,
                                onValueChange = { chapterQuery = it },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Фильтр глав по названию") },
                                singleLine = true,
                                trailingIcon = {
                                    if (chapterQuery.isNotEmpty()) IconButton(onClick = { chapterQuery = "" }, enabled = !busy) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить фильтр глав")
                                    }
                                },
                            )
                        }
                        if (visibleChapters.isNotEmpty()) item(key = "selection") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val allSelected = selected.size == visibleChapters.size
                                Row(
                                    Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(
                                        value = allSelected,
                                        role = Role.Checkbox,
                                        enabled = !busy,
                                        onValueChange = { selectedIds = if (it) visibleIds else emptySet() },
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = allSelected, onCheckedChange = null, enabled = !busy)
                                    Text("Выбрать все показанные · выбрано ${selected.size}", modifier = Modifier.padding(start = 8.dp))
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = selected.isNotEmpty() && !busy, onClick = { requestAction(ChapterAction.DOWNLOAD, selected) }) { Text("Скачать текст") }
                                    OutlinedButton(enabled = selected.isNotEmpty() && !busy && settings != null, onClick = { requestAction(ChapterAction.RENDER, selected) }) { Text("Озвучить") }
                                    OutlinedButton(enabled = selected.isNotEmpty() && !busy, onClick = { requestAction(ChapterAction.CANCEL, selected) }) { Text("Остановить") }
                                    OutlinedButton(enabled = selected.isNotEmpty() && !busy, onClick = { deleteMode = DeleteMode.AUDIO; requestAction(ChapterAction.DELETE, selected) }) { Text("Удалить данные") }
                                    TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { selectionMenu = true }) { Text("Отметить…") }
                                    DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                        DropdownMenuItem(text = { Text("Прочитано") }, onClick = { selectionMenu = false; requestAction(ChapterAction.READ, selected) })
                                        DropdownMenuItem(text = { Text("Не прочитано") }, onClick = { selectionMenu = false; requestAction(ChapterAction.UNREAD, selected) })
                                        DropdownMenuItem(text = { Text("Снять выбор") }, onClick = { selectionMenu = false; selectedIds = emptySet() })
                                    }
                                }
                            }
                        }
                        if (visibleChapters.isEmpty()) item(key = "no-chapters") {
                            Text(if (chapters.isEmpty()) "Глав пока нет. Попробуйте обновить книгу." else "Нет глав для этого переводчика и фильтра.")
                        }
                        items(visibleChapters, key = { "chapter-${it.id}" }, contentType = { "chapter" }) { chapter ->
                            ChapterRow(
                                chapter = chapter,
                                work = workByChapter[chapter.id].orEmpty(),
                                selected = chapter.id in selected,
                                enabled = !busy,
                                onSelect = { selectedIds = if (it) selected + chapter.id else selected - chapter.id },
                                onOpen = { onRead(chapter.id) },
                                onDownload = { requestAction(ChapterAction.DOWNLOAD, listOf(chapter.id)) },
                                onRender = { requestAction(ChapterAction.RENDER, listOf(chapter.id)) },
                                onCancel = { runOperation("Задачи главы остановлены") { repository.cancelJobs(listOf(chapter.id)) } },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { pending ->
        ChapterActionDialog(
            pending = pending,
            settings = settings,
            deleteMode = deleteMode,
            onDeleteMode = { deleteMode = it },
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                val mode = deleteMode
                runOperation(when (pending.action) {
                    ChapterAction.DOWNLOAD -> "Загрузка текста добавлена в очередь"
                    ChapterAction.RENDER -> "Озвучка добавлена в очередь"
                    ChapterAction.CANCEL -> "Задачи остановлены"
                    ChapterAction.DELETE -> "Локальные данные удалены"
                    ChapterAction.READ, ChapterAction.UNREAD -> "Отметки чтения обновлены"
                }) {
                    when (pending.action) {
                        ChapterAction.DOWNLOAD -> repository.enqueueText(pending.ids)
                        ChapterAction.RENDER -> repository.enqueueAudio(pending.ids)
                        ChapterAction.CANCEL -> repository.cancelJobs(pending.ids)
                        ChapterAction.DELETE -> repository.deleteChapterData(pending.ids, mode)
                        ChapterAction.READ -> repository.markRead(pending.ids, true)
                        ChapterAction.UNREAD -> repository.markRead(pending.ids, false)
                    }
                }
            },
        )
    }
    if (deleteBook) AlertDialog(
        onDismissRequest = { deleteBook = false },
        title = { Text("Удалить книгу?") },
        text = {
            Text("«${novel?.name.orEmpty()}» будет удалена вместе со всеми скачанными главами и аудио. Задачи книги будут остановлены. Это действие нельзя отменить. Чтобы только убрать книгу из библиотеки, используйте кнопку на странице книги.")
        },
        confirmButton = {
            TextButton(onClick = {
                deleteBook = false
                runOperation("Книга удалена") { repository.deleteNovel(novelId); onBack() }
            }) { Text("Удалить книгу", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleteBook = false }) { Text("Отмена") } },
    )
}

@Composable
private fun NovelMetadata(novel: NovelEntity, sourceName: String) {
    var expanded by rememberSaveable(novel.id) { mutableStateOf(false) }
    val description = remember(novel.summary) { novel.summary?.let(::htmlToText).orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovelCover(novel.coverUrl, novel.name, width = 96.dp, height = 138.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(novel.name, style = MaterialTheme.typography.titleLarge)
                Text(sourceName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                novel.author?.takeIf { it.isNotBlank() }?.let { Text("Автор: $it", style = MaterialTheme.typography.bodyMedium) }
                Text(when (novel.status) {
                    "ONGOING" -> "Продолжается"
                    "COMPLETED" -> "Завершено"
                    "ON_HIATUS" -> "На паузе"
                    "CANCELLED" -> "Выпуск прекращён"
                    else -> "Статус не указан"
                }, style = MaterialTheme.typography.bodySmall)
                novel.rating?.let { Text("Рейтинг: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        novel.genres?.takeIf { it.isNotBlank() }?.let { Text("Жанры: $it", style = MaterialTheme.typography.bodyMedium) }
        if (description.isNotBlank()) {
            Text(description, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть описание" else "Полное описание") }
        } else Text("Описание отсутствует", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChapterActionDialog(
    pending: ChapterConfirmation,
    settings: AppSettings?,
    deleteMode: DeleteMode,
    onDeleteMode: (DeleteMode) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (pending.action) {
        ChapterAction.DOWNLOAD -> "Скачать текст?"
        ChapterAction.RENDER -> "Озвучить главы?"
        ChapterAction.CANCEL -> "Остановить задачи?"
        ChapterAction.DELETE -> "Удалить локальные данные?"
        ChapterAction.READ -> "Отметить как прочитанные?"
        ChapterAction.UNREAD -> "Отметить как непрочитанные?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Глав: ${pending.ids.size}")
                when (pending.action) {
                    ChapterAction.DOWNLOAD -> Text("Текст будет скачан с сайта источника. Готовые главы можно открыть из списка. Для закрытых глав может потребоваться вход или покупка на сайте.")
                    ChapterAction.RENDER -> {
                        Text("Модель: ${settings?.modelId ?: "не выбрана"} · голос: ${settings?.speaker ?: "не выбран"}")
                        Text("Недостающий текст будет скачан. Озвучка выполняется в фоне и может занять много времени. Повторная озвучка заменяет прежнее аудио и сбрасывает его позицию.")
                    }
                    ChapterAction.CANCEL -> Text("Загрузка текста и озвучка выбранных глав будут остановлены. Уже сохранённые данные останутся.")
                    ChapterAction.DELETE -> {
                        Text("Выберите данные для удаления. Сами главы останутся в списке. Задачи выбранных глав будут остановлены. Удаление нельзя отменить.")
                        listOf(
                            DeleteMode.AUDIO to "Только аудио и текст озвучки",
                            DeleteMode.TEXT to "Только скачанный текст, оставить аудио",
                            DeleteMode.ALL to "Весь текст и аудио",
                        ).forEach { (mode, label) ->
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).selectable(
                                    selected = deleteMode == mode,
                                    role = Role.RadioButton,
                                    onClick = { onDeleteMode(mode) },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = deleteMode == mode, onClick = null)
                                Text(label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    ChapterAction.READ -> Text("Изменится только отметка чтения выбранных глав. Текст и аудио сохранятся.")
                    ChapterAction.UNREAD -> Text("Отметка «Прочитано» будет снята. Текст и аудио сохранятся.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = pending.action != ChapterAction.RENDER || settings != null) {
                Text(when (pending.action) {
                    ChapterAction.DOWNLOAD -> "Скачать"
                    ChapterAction.RENDER -> "Озвучить"
                    ChapterAction.CANCEL -> "Остановить"
                    ChapterAction.DELETE -> "Удалить"
                    ChapterAction.READ, ChapterAction.UNREAD -> "Отметить"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
