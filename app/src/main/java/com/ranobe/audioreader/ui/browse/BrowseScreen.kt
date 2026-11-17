package com.ranobe.audioreader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ranobe.audioreader.data.remote.BrowseOptions
import com.ranobe.audioreader.data.remote.NovelItem
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.ui.library.ErrorMessage
import com.ranobe.audioreader.ui.library.LoadingMessage
import com.ranobe.audioreader.ui.library.NovelCover
import com.ranobe.audioreader.ui.library.isAuthenticationError
import com.ranobe.audioreader.ui.library.uiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenAuth: (String) -> Unit,
) {
    var sourceId by rememberSaveable { mutableStateOf(container.sources.firstOrNull()?.id.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var latest by rememberSaveable { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<NovelItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var openingItem by remember { mutableStateOf<NovelItem?>(null) }
    var openingError by remember { mutableStateOf<String?>(null) }
    var openingRetry by remember { mutableIntStateOf(0) }
    val source = remember(container, sourceId) { container.sourceById(sourceId) }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(generation) {
        if (generation > 0) listState.scrollToItem(0)
    }

    fun resetResults() {
        generation++
        page = 1
        results = emptyList()
        loading = false
        error = null
        hasMore = false
        openingItem = null
        openingError = null
    }

    fun changeQuery(value: String) {
        query = value
        submitted = false
        resetResults()
    }

    fun submitSearch() {
        keyboard?.hide()
        submitted = query.isNotBlank()
        resetResults()
    }

    LaunchedEffect(source, query, submitted, latest, generation, page, retry) {
        val requestGeneration = generation
        val requestPage = page
        val requestRetry = retry
        fun isCurrentRequest() = generation == requestGeneration && page == requestPage && retry == requestRetry
        val currentSource = source ?: return@LaunchedEffect
        if (query.isNotBlank() && !submitted) return@LaunchedEffect
        loading = true
        error = null
        try {
            val found = if (submitted) currentSource.searchNovels(query.trim(), requestPage)
            else currentSource.popularNovels(requestPage, BrowseOptions(latest = latest))
            currentCoroutineContext().ensureActive()
            if (!isCurrentRequest()) return@LaunchedEffect
            val existing = if (requestPage == 1) emptyList() else results
            val knownPaths = existing.mapTo(HashSet()) { it.path }
            val additions = found.filter { knownPaths.add(it.path) }
            results = existing + additions
            hasMore = additions.isNotEmpty() && (!submitted || currentSource.id == "rulate")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrentRequest()) error = failure.uiMessage()
        } finally {
            if (isCurrentRequest()) loading = false
        }
    }

    LaunchedEffect(openingItem, sourceId, generation, openingRetry) {
        val item = openingItem ?: return@LaunchedEffect
        val requestGeneration = generation
        val requestSource = sourceId
        val requestRetry = openingRetry
        openingError = null
        try {
            val id = container.libraryRepository.fetchNovel(requestSource, item.path)
            currentCoroutineContext().ensureActive()
            if (generation == requestGeneration && openingItem?.path == item.path && sourceId == requestSource) {
                openingItem = null
                onOpenNovel(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == requestGeneration && openingRetry == requestRetry && openingItem?.path == item.path) openingError = failure.uiMessage()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Найти книгу") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            },
        )
    }) { padding ->
        LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "sources") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(container.sources, key = { it.id }) { candidate ->
                            FilterChip(
                                selected = sourceId == candidate.id,
                                onClick = {
                                    if (sourceId != candidate.id) {
                                        sourceId = candidate.id
                                        query = ""
                                        submitted = false
                                        resetResults()
                                    }
                                },
                                label = { Text(candidate.name) },
                            )
                        }
                    }
                }
                item(key = "query") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = ::changeQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Поиск по названию") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { changeQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить поисковый запрос")
                            }
                        },
                    )
                }
                item(key = "search") {
                    Button(onClick = ::submitSearch, enabled = source != null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (query.isBlank()) "Обновить каталог" else "Найти")
                    }
                }
                item(key = "modes") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = !submitted && query.isBlank() && !latest,
                                onClick = { latest = false; query = ""; submitted = false; resetResults() },
                                label = { Text("Популярное") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = !submitted && query.isBlank() && latest,
                                onClick = { latest = true; query = ""; submitted = false; resetResults() },
                                label = { Text("Последние обновления") },
                            )
                        }
                    }
                }
                if (source?.requiresAuth == true) item(key = "auth") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Для части книг и глав нужен вход в ${source.name}.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onOpenAuth(source.id) }) { Text("Войти в ${source.name}") }
                    }
                }
                if (source == null) item(key = "no-source") {
                    Text("Источник недоступен. Выберите другой источник.")
                }
                if (query.isNotBlank() && !submitted) item(key = "prompt") {
                    Text("Введите название и нажмите «Найти».")
                }
                items(results, key = { "book-${it.path}" }, contentType = { "book" }) { book ->
                    Card(Modifier.fillMaxWidth().clickable(
                        role = Role.Button,
                        onClickLabel = "Открыть книгу ${book.name}",
                        onClick = { openingError = null; openingItem = book },
                    )) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            NovelCover(book.coverUrl, book.name)
                            Text(
                                book.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (loading) item(key = "loading") {
                    LoadingMessage(if (page == 1) "Загружаем книги…" else "Загружаем следующую страницу…")
                }
                error?.let { message ->
                    item(key = "error") {
                        ErrorMessage(
                            message = message,
                            onRetry = { retry++ },
                            onLogin = if (source?.requiresAuth == true) ({ onOpenAuth(source.id) }) else null,
                        )
                    }
                }
                if (!loading && error == null && source != null && (query.isBlank() || submitted)) {
                    if (results.isEmpty()) item(key = "empty") {
                        Text("Книги не найдены. Попробуйте другое название или источник.")
                    }
                    if (hasMore) item(key = "more") {
                        OutlinedButton(
                            onClick = { loading = true; page++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Ещё") }
                    }
                    if (submitted && source.id != "rulate" && results.isNotEmpty()) item(key = "search-end") {
                        Text("Источник возвращает поиск без страниц. Чтобы уточнить результаты, измените запрос.", style = MaterialTheme.typography.bodySmall)
                    }
                }
        }
    }

    openingItem?.let { book ->
        AlertDialog(
            onDismissRequest = { openingItem = null },
            title = { Text(if (openingError == null) "Открытие книги" else "Не удалось открыть книгу") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(book.name)
                    if (openingError == null) LoadingMessage("Загружаем описание и главы…")
                    else {
                        Text(openingError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        if (source?.requiresAuth == true) {
                            Text(
                                if (openingError.orEmpty().isAuthenticationError())
                                    "Источник ограничил доступ. Войдите и повторите запрос. Платные главы требуют доступа на сайте."
                                else "Возможно, для этой книги требуется вход на сайт источника.",
                            )
                            TextButton(onClick = { onOpenAuth(source.id) }) { Text("Войти на сайт") }
                        }
                    }
                }
            },
            confirmButton = {
                if (openingError != null) TextButton(onClick = { openingError = null; openingRetry++ }) { Text("Повторить") }
            },
            dismissButton = { TextButton(onClick = { openingItem = null }) { Text("Отмена") } },
        )
    }
}
