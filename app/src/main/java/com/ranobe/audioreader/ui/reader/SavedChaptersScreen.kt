package com.ranobe.audioreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ranobe.audioreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedChaptersScreen(container: AppContainer, onBack: () -> Unit, onOpenChapter: (Long) -> Unit) {
    val chapters by remember(container) { container.chapterDao.observeDownloaded() }.collectAsStateWithLifecycle(emptyList())
    val novels by remember(container) { container.novelDao.observeLibrary() }.collectAsStateWithLifecycle(emptyList())
    val names = novels.associate { it.id to it.name }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Скачанные главы") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        })
    }) { padding ->
        if (chapters.isEmpty()) Column(Modifier.padding(padding).padding(24.dp)) {
            Text("Скачанных глав пока нет. Здесь появятся главы с сохранённым текстом или аудио.")
        }
        else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(chapters, key = { it.id }) { chapter ->
                ListItem(
                    headlineContent = { Text(chapter.name) },
                    overlineContent = { Text(names[chapter.novelId] ?: "Книга ${chapter.novelId}") },
                    supportingContent = { Text(if (chapter.audioPath != null) "Аудио · ${ReaderText.time(chapter.durationMs)}" else "Только текст") },
                    trailingContent = { if (chapter.isRead) Text("Прочитано") },
                    modifier = Modifier.fillMaxWidth().clickable { onOpenChapter(chapter.id) },
                )
            }
        }
    }
}
