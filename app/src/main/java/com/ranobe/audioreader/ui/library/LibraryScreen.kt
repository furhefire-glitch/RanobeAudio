package com.ranobe.audioreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ranobe.audioreader.data.local.entity.NovelEntity
import com.ranobe.audioreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onBrowse: () -> Unit,
    onOpenNovel: (Long) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onDictionary: () -> Unit,
    onStorage: () -> Unit,
    onSaved: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val libraryFlow = remember(container) { container.libraryRepository.library }
    val state by observeScreenFlow(libraryFlow, retry)
    val novels = (state as? ScreenLoad.Ready)?.data.orEmpty()
    val filtered = remember(novels, query) {
        novels.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Библиотека") },
            actions = {
                IconButton(onClick = onBrowse) {
                    Icon(Icons.Default.Search, contentDescription = "Найти книгу в источниках")
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Разделы приложения")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(
                        "Источники" to onBrowse,
                        "Модели и голоса" to onModels,
                        "Скачанные главы" to onSaved,
                        "Словарь" to onDictionary,
                        "Хранилище" to onStorage,
                        "Настройки" to onSettings,
                    ).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menuOpen = false
                            action()
                        })
                    }
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "filter") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название книги") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить фильтр библиотеки")
                        }
                    },
                )
            }
            when (val current = state) {
                ScreenLoad.Loading -> item(key = "loading") { LoadingMessage("Загружаем библиотеку…") }
                is ScreenLoad.Error -> item(key = "error") {
                    ErrorMessage(current.message, onRetry = { retry++ })
                }
                is ScreenLoad.Ready -> {
                    if (filtered.isEmpty()) item(key = "empty") {
                        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (novels.isEmpty()) "В библиотеке пока нет книг" else "Ничего не найдено",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (novels.isEmpty()) "Откройте источник, найдите книгу и добавьте её в библиотеку."
                                else "Измените название или найдите книгу в источниках.",
                            )
                            Button(onClick = onBrowse) { Text("Найти книгу") }
                        }
                    }
                    items(filtered, key = { "novel-${it.id}" }, contentType = { "novel" }) { novel ->
                        LibraryNovelCard(novel, onOpen = { onOpenNovel(novel.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryNovelCard(novel: NovelEntity, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(
        role = Role.Button,
        onClickLabel = "Открыть книгу ${novel.name}",
        onClick = onOpen,
    )) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelCover(novel.coverUrl, novel.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(novel.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                novel.author?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (novel.lastReadChapterId != null) "Продолжить чтение →" else "Открыть книгу →",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
