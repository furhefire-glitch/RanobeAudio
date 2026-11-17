package com.ranobe.audioreader.domain.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ExecutionException

@androidx.annotation.OptIn(UnstableApi::class)
class AudioPlayerController(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var connection: Connection? = null
    private var localError: String? = null

    suspend fun connect() {
        var attempt: Connection? = null
        var accepted = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (connection?.controller?.isConnected == false) releaseConnection()
                val current = connection ?: createConnection()
                attempt = current
                current.waiters++
                try {
                    current.ready.await()
                    check(connection === current && current.controller?.isConnected == true) { "Плеер отключён" }
                    current.acceptedConnections++
                    accepted = true
                    refresh(current)
                } finally {
                    current.waiters--
                    if (connection === current && current.waiters == 0 && current.acceptedConnections == 0L) {
                        releaseConnection()
                    }
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                attempt?.let { current ->
                    if (accepted) current.acceptedConnections--
                    if (connection === current && current.waiters == 0 && current.acceptedConnections == 0L) {
                        releaseConnection()
                    }
                }
            }
            throw error
        } catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (connection == null || connection === attempt) {
                    showError(error.message ?: "Не удалось подключиться к плееру")
                }
            }
        }
    }

    fun disconnect() = onMain { releaseConnection() }

    suspend fun openChapter(chapterId: Long, play: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (chapterId <= 0) {
            showError("Некорректный идентификатор главы")
            return@withContext
        }
        connect()
        val current = connection ?: return@withContext
        val controller = current.controller ?: return@withContext
        val operation = current.scope.async(start = CoroutineStart.UNDISPATCHED) {
            sendCommand(controller, PlaybackCommands.openChapter, Bundle().apply {
                putLong(PlaybackCommands.CHAPTER_ID, chapterId)
                putBoolean(PlaybackCommands.PLAY, play)
            })
        }
        try {
            val result = operation.await()
            if (connection === current) applyResult(current, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (connection === current) showError(error.message ?: "Не удалось открыть главу")
        } finally {
            operation.cancel()
        }
    }

    fun togglePlayPause() = withController { controller ->
        if (controller.playWhenReady && controller.playbackState != Player.STATE_ENDED && controller.playerError == null) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) = withController { controller ->
        val duration = controller.duration
        val position = positionMs.coerceAtLeast(0)
        controller.seekTo(if (duration > 0 && duration != C.TIME_UNSET) position.coerceAtMost(duration) else position)
    }

    fun setSpeed(speed: Float) = withController { controller ->
        if (!speed.isFinite()) {
            showError("Некорректная скорость воспроизведения")
        } else {
            controller.setPlaybackSpeed(PlaybackCommands.clampSpeed(speed))
        }
    }

    fun setSleepTimer(minutes: Int) = onMain {
        if (minutes !in 0..180) {
            showError("Таймер сна должен быть от 0 до 180 минут")
            return@onMain
        }
        val current = connection
        val controller = current?.controller
        if (current == null || controller?.isConnected != true) {
            showError("Плеер не подключён")
            return@onMain
        }
        current.scope.launch {
            try {
                val result = sendCommand(controller, PlaybackCommands.sleepTimer, Bundle().apply {
                    putInt(PlaybackCommands.MINUTES, minutes)
                })
                if (connection === current) applyResult(current, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connection === current) showError(error.message ?: "Не удалось установить таймер сна")
            }
        }
    }

    private fun createConnection(): Connection {
        val current = Connection()
        connection = current
        localError = null
        mutableState.value = mutableState.value.copy(error = null)
        try {
            val token = SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))
            current.future = MediaController.Builder(applicationContext, token)
                .setApplicationLooper(Looper.getMainLooper())
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (connection !== current) return
                        val error = IOException("Соединение с плеером потеряно")
                        showError("Соединение с плеером потеряно")
                        releaseConnection(error)
                    }

                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        refresh(current)
                    }

                    override fun onError(controller: MediaController, sessionError: SessionError) {
                        if (connection === current) {
                            localError = sessionError.message
                            refresh(current)
                        }
                    }
                })
                .buildAsync()
            current.future!!.addListener(
                {
                    if (connection === current) {
                        try {
                            val controller = current.future!!.get()
                            if (!controller.isConnected) throw IOException("Плеер отклонил подключение")
                            current.controller = controller
                            controller.addListener(playerListener)
                            current.ready.complete(controller)
                            refresh(current)
                            current.scope.launch {
                                while (isActive) {
                                    delay(250)
                                    refresh(current)
                                }
                            }
                        } catch (error: Exception) {
                            val cause = if (error is ExecutionException) error.cause ?: error else error
                            if (connection === current) {
                                showError(cause.message ?: "Не удалось подключиться к плееру")
                                releaseConnection(cause)
                            }
                        }
                    }
                },
                mainExecutor,
            )
        } catch (error: Exception) {
            releaseConnection()
            throw error
        }
        return current
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            connection?.let { refresh(it) }
        }
    }

    private suspend fun sendCommand(controller: MediaController, command: SessionCommand, args: Bundle): SessionResult {
        if (!controller.isConnected) throw IOException("Плеер отключён")
        return controller.sendCustomCommand(command, args).awaitPlaybackResult(mainExecutor)
    }

    private fun applyResult(current: Connection, result: SessionResult) {
        localError = if (result.resultCode == SessionResult.RESULT_SUCCESS) null else {
            result.sessionError?.message ?: "Команда плеера не выполнена (${result.resultCode})"
        }
        refresh(current)
    }

    private fun withController(action: (MediaController) -> Unit) = onMain {
        val current = connection
        val controller = current?.controller
        if (current == null || controller?.isConnected != true) {
            showError("Плеер не подключён")
            return@onMain
        }
        try {
            localError = null
            action(controller)
            refresh(current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showError(error.message ?: "Не удалось выполнить команду плеера")
        }
    }

    private fun refresh(current: Connection) {
        if (connection !== current) return
        val controller = current.controller ?: return
        if (!controller.isConnected) return
        val extras = controller.sessionExtras
        val deadline = extras.getLong(PlaybackCommands.SLEEP_DEADLINE, 0L)
        val duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: controller.mediaMetadata.extras?.getLong(PlaybackCommands.DURATION, 0L) ?: 0L
        mutableState.value = PlaybackState(
            chapterId = controller.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = controller.isPlaying,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            speed = controller.playbackParameters.speed,
            sleepRemainingMs = if (deadline == 0L) 0L else (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
            error = controller.playerError?.let { it.message ?: "Ошибка воспроизведения (${it.errorCode})" }
                ?: localError ?: extras.getString(PlaybackCommands.ERROR),
            connected = true,
        )
    }

    private fun showError(message: String) {
        localError = message
        mutableState.value = mutableState.value.copy(error = message)
    }

    private fun releaseConnection(failure: Throwable? = null) {
        val current = connection
        connection = null
        mutableState.value = mutableState.value.copy(connected = false, isPlaying = false, isBuffering = false)
        if (current != null) {
            current.controller?.removeListener(playerListener)
            val future = current.future
            current.future = null
            current.controller = null
            if (failure != null) current.ready.completeExceptionally(failure)
            current.scope.cancel()
            future?.let { MediaController.releaseFuture(it) }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private class Connection {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CompletableDeferred<MediaController>(scope.coroutineContext[Job])
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var waiters = 0
        var acceptedConnections = 0L
    }
}
