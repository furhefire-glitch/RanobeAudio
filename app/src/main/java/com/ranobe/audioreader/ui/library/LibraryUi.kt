package com.ranobe.audioreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal sealed interface ScreenLoad<out T> {
    data object Loading : ScreenLoad<Nothing>
    data class Ready<T>(val data: T) : ScreenLoad<T>
    data class Error(val message: String) : ScreenLoad<Nothing>
}

@Composable
internal fun <T> observeScreenFlow(flow: Flow<T>, retry: Int = 0): State<ScreenLoad<T>> {
    val owner = LocalLifecycleOwner.current
    val state = remember(flow, retry, owner) { mutableStateOf<ScreenLoad<T>>(ScreenLoad.Loading) }
    LaunchedEffect(flow, retry, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                flow.collect { state.value = ScreenLoad.Ready(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = ScreenLoad.Error(error.uiMessage())
            }
        }
    }
    return state
}

internal fun Throwable.uiMessage(): String = message?.takeIf { it.isNotBlank() }
    ?: "Не удалось выполнить действие. Проверьте подключение и повторите попытку."

internal fun String.isAuthenticationError(): Boolean =
    Regex("\\b(401|403)\\b|авториза|войд|доступ|unauthori|forbidden|log.?in", RegexOption.IGNORE_CASE)
        .containsMatchIn(this)

@Composable
internal fun NovelCover(url: String?, title: String, width: Dp = 64.dp, height: Dp = 90.dp) {
    Box(
        modifier = Modifier.width(width).height(height)
            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = url,
            contentDescription = "Обложка: $title",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
internal fun LoadingMessage(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ErrorMessage(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onLogin: (() -> Unit)? = null,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (onLogin != null) {
                Text(
                    if (message.isAuthenticationError())
                        "Источник ограничил доступ. Войдите в аккаунт и повторите запрос. Вход не снимает платные ограничения."
                    else "Если источник требует аккаунт, войдите на сайт и повторите запрос.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetry) { Text("Повторить") }
            if (onLogin != null) TextButton(onClick = onLogin) { Text("Войти на сайт") }
        }
    }
}
