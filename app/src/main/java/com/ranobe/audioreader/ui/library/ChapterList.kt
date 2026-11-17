package com.ranobe.audioreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.ranobe.audioreader.data.local.entity.AudioStatus
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import java.util.Locale

internal fun ChapterEntity.hasLocalContent(): Boolean =
    !html.isNullOrBlank() || !renderedText.isNullOrBlank() || !audioPath.isNullOrBlank()

internal fun ChapterEntity.branchKey(): String = scanlator?.trim().orEmpty()

internal fun chapterDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun groupChapterWork(work: List<WorkInfo>): Map<Long, List<WorkInfo>> {
    val result = mutableMapOf<Long, MutableList<WorkInfo>>()
    work.forEach { info ->
        info.tags.forEach { tag ->
            if (tag.startsWith("chapter-")) tag.removePrefix("chapter-").toLongOrNull()?.let { id ->
                result.getOrPut(id) { mutableListOf() }.add(info)
            }
        }
    }
    return result
}

private fun List<WorkInfo>.activeWithTag(tag: String): WorkInfo? =
    filter { tag in it.tags && !it.state.isFinished }
        .minByOrNull { if (it.state == WorkInfo.State.RUNNING) 0 else 1 }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChapterRow(
    chapter: ChapterEntity,
    work: List<WorkInfo>,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
) {
    val textWork = work.activeWithTag("text")
    val renderWork = work.activeWithTag("render")
    val textError = if (textWork == null && chapter.html.isNullOrBlank())
        work.firstOrNull { "text" in it.tags && it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString("error") ?: work.firstOrNull { "text" in it.tags && it.state == WorkInfo.State.FAILED }
            ?.let { "Не удалось скачать текст" }
    else null
    val audioError = if (renderWork == null && chapter.audioStatus == AudioStatus.ERROR)
        work.firstOrNull { "render" in it.tags && it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString("error") ?: "Не удалось озвучить главу"
    else null
    val canCancel = work.any { !it.state.isFinished } ||
        chapter.audioStatus == AudioStatus.QUEUED || chapter.audioStatus == AudioStatus.RENDERING
    val canRead = chapter.hasLocalContent()
    val done = renderWork?.progress?.getInt("done", 0) ?: 0
    val total = renderWork?.progress?.getInt("total", 0) ?: 0

    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(
                enabled = canRead || enabled,
                role = Role.Button,
                onClickLabel = if (canRead) "Читать ${chapter.name}" else "Скачать ${chapter.name}",
                onClick = if (canRead) onOpen else onDownload,
            ).padding(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelect,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Выбрать главу: ${chapter.name}" },
            )
            Column(Modifier.weight(1f).padding(top = 12.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chapter.name, style = MaterialTheme.typography.titleSmall)
                if (chapter.isRead) Text("Прочитано", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when {
                        textWork?.state == WorkInfo.State.RUNNING -> "Текст: скачивается"
                        textWork != null -> "Текст: в очереди, ожидает условий загрузки"
                        !chapter.html.isNullOrBlank() -> "Текст: сохранён"
                        textError != null -> "Текст: ошибка загрузки"
                        !chapter.renderedText.isNullOrBlank() -> "Текст озвучки: сохранён"
                        else -> "Текст: не скачан"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        renderWork?.state == WorkInfo.State.RUNNING && total > 0 -> "Озвучка: ${done.coerceIn(0, total)} из $total частей"
                        renderWork?.state == WorkInfo.State.RUNNING -> "Озвучка: подготовка"
                        renderWork != null -> "Аудио: в очереди, ожидает условий озвучки"
                        chapter.audioStatus == AudioStatus.RENDERING -> "Озвучка выполняется"
                        chapter.audioStatus == AudioStatus.QUEUED -> "Аудио: в очереди"
                        chapter.audioStatus == AudioStatus.ERROR -> "Аудио: ошибка озвучки"
                        !chapter.audioPath.isNullOrBlank() -> "Аудио: сохранено"
                        else -> "Аудио: не создано"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (chapter.durationMs > 0) Text(
                    "Длительность ${chapterDuration(chapter.durationMs)} · позиция ${chapterDuration(chapter.positionMs.coerceIn(0, chapter.durationMs))}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (renderWork?.state == WorkInfo.State.RUNNING && total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (renderWork?.state == WorkInfo.State.RUNNING || textWork?.state == WorkInfo.State.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                listOfNotNull(textError, audioError).distinct().forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canRead) TextButton(onClick = onOpen) { Text("Читать") }
                    if (chapter.html.isNullOrBlank() && textWork == null && renderWork == null) {
                        TextButton(onClick = onDownload, enabled = enabled) {
                            Text(if (textError != null) "Повторить загрузку" else "Скачать текст")
                        }
                    }
                    if (audioError != null) TextButton(onClick = onRender, enabled = enabled) { Text("Повторить озвучку") }
                    if (canCancel) TextButton(
                        onClick = onCancel,
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Остановить задачи главы: ${chapter.name}" },
                    ) { Text("Остановить") }
                }
            }
        }
    }
}
