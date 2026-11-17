package com.ranobe.audioreader.domain.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.room.withTransaction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ranobe.audioreader.MainActivity
import com.ranobe.audioreader.RanobeAudioApp
import com.ranobe.audioreader.data.local.entity.ChapterEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val container get() = (application as RanobeAudioApp).container
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resumePolicy = PlaybackResumePolicy()
    private val openMutex = Mutex()
    private val chapters = mutableMapOf<Long, ChapterEntity>()
    private val completed = mutableSetOf<Long>()
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var sessionPlayer: ResumePlayer
    private var session: MediaSession? = null
    private var replacingPlaylist = false
    private var destroyed = false
    private var sleepDeadlineMs = 0L
    private var sleepJob: Job? = null
    private var serviceError: String? = null
    private var speedRevision = 0L

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        sessionPlayer = ResumePlayer(
            delegate = exoPlayer,
            resumePolicy = resumePolicy,
            restartCompletedChapter = ::restartCompletedChapter,
            beforeExplicitSeek = { completed.remove(currentChapterId()) },
            afterExplicitSeek = { saveCurrent() },
            onSpeedRequested = { speedRevision++ },
        )
        exoPlayer.addListener(playerListener)
        PlaybackAccess.detachHandler = ::detachChapters
        serviceScope.launch {
            container.settingsRepository.flow.collect { resumePolicy.rewindMs = it.rewindSeconds * 1000L }
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(RanobeAudioApp.CHANNEL_PLAYBACK)
                .build(),
        )
        session = MediaSession.Builder(this, sessionPlayer)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity(null))
            .build()
        publishExtras()
        serviceScope.launch {
            try {
                writeTail?.join()
                val speed = withContext(Dispatchers.IO) {
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).getFloat(SPEED, 1f)
                }
                if (speedRevision == 0L) {
                    exoPlayer.setPlaybackSpeed(PlaybackCommands.clampSpeed(speed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportError("Не удалось загрузить скорость воспроизведения", error)
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(3_000)
                if (exoPlayer.isPlaying) {
                    saveCurrent()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { isAllowed(controllerInfo) }

    private fun isAllowed(controller: MediaSession.ControllerInfo): Boolean =
        (controller.packageName == packageName && controller.uid == applicationInfo.uid) || controller.isTrusted

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!isAllowed(controller)) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PlaybackCommands.openChapter)
                .add(PlaybackCommands.sleepTimer)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(
                    Player.Commands.Builder().addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                        Player.COMMAND_GET_VOLUME,
                        Player.COMMAND_SET_VOLUME,
                    ).build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!isAllowed(controller)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            return when (customCommand.customAction) {
                PlaybackCommands.OPEN_CHAPTER -> {
                    @Suppress("DEPRECATION")
                    val chapterId = args.get(PlaybackCommands.CHAPTER_ID) as? Long
                    @Suppress("DEPRECATION")
                    val playValue = args.get(PlaybackCommands.PLAY)
                    if (chapterId == null || chapterId <= 0 || (playValue != null && playValue !is Boolean)) {
                        invalidRequest("Некорректный идентификатор главы или флаг воспроизведения")
                    } else {
                        commandFuture {
                            openMutex.withLock {
                                PlaybackAccess.mutex.withLock { openChapter(chapterId, playValue == true) }
                            }
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        }
                    }
                }
                PlaybackCommands.SLEEP_TIMER -> {
                    @Suppress("DEPRECATION")
                    val minutes = args.get(PlaybackCommands.MINUTES) as? Int
                    if (minutes == null || minutes !in 0..180) {
                        invalidRequest("Таймер сна должен быть от 0 до 180 минут")
                    } else {
                        setSleepTimer(minutes)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFailedFuture(SecurityException("Используйте OPEN_CHAPTER вместо URI"))

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFailedFuture(SecurityException("Используйте OPEN_CHAPTER вместо URI"))
    }

    private fun invalidRequest(message: String): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionError(SessionError.ERROR_BAD_VALUE, message)))

    private fun commandFuture(block: suspend () -> SessionResult): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                result.set(block())
            } catch (error: CancellationException) {
                result.cancel(false)
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "Не удалось открыть аудиоглаву"
                reportError(message, error)
                val code = if (error is IllegalArgumentException) SessionError.ERROR_BAD_VALUE else SessionError.ERROR_IO
                result.set(SessionResult(SessionError(code, message)))
            }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, ContextCompat.getMainExecutor(this))
        job.invokeOnCompletion { if (it is CancellationException) result.cancel(false) }
        job.start()
        return result
    }

    private suspend fun openChapter(chapterId: Long, play: Boolean) {
        writeTail?.join()
        val playlist = withContext(Dispatchers.IO) { resolvePlaylist(chapterId) }
        currentCoroutineContext().ensureActive()
        val requestedUri = playlist.entries[playlist.startIndex].item.localConfiguration?.uri
        if (openCurrentChapter(chapterId, requestedUri, play)) return
        saveCurrent()
        replacingPlaylist = true
        try {
            exoPlayer.pause()
            chapters.clear()
            completed.clear()
            playlist.entries.forEach { entry ->
                chapters[entry.chapter.id] = entry.chapter
                if (isCompleted(entry.chapter)) completed.add(entry.chapter.id) else completed.remove(entry.chapter.id)
            }
            completed.remove(chapterId)
            exoPlayer.setMediaItems(playlist.entries.map { it.item }, playlist.startIndex, playlist.positionMs)
            exoPlayer.prepare()
        } finally {
            replacingPlaylist = false
        }
        resumePolicy.onChapterOpened(playlist.positionMs)
        session?.setSessionActivity(sessionActivity(chapterId))
        serviceError = null
        publishExtras()
        saveCurrent()
        if (play) sessionPlayer.play()
    }

    private fun openCurrentChapter(chapterId: Long, uri: Uri?, play: Boolean): Boolean {
        if (currentChapterId() != chapterId || exoPlayer.currentMediaItem?.localConfiguration?.uri != uri) return false
        if (chapterId in completed || exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.pause()
            restartCompletedChapter()
        }
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        serviceError = null
        publishExtras()
        if (play) sessionPlayer.play()
        return true
    }

    private suspend fun resolvePlaylist(chapterId: Long): ResolvedPlaylist {
        val requested = container.chapterDao.getById(chapterId)
            ?: throw IllegalArgumentException("Глава не найдена")
        val novel = container.novelDao.getById(requested.novelId)
            ?: throw IllegalArgumentException("Книга не найдена")
        val ordered = container.chapterDao.getByNovel(requested.novelId)
            .sortedWith(compareBy<ChapterEntity> { it.sortOrder }.thenBy { it.id })
        val requestedIndex = ordered.indexOfFirst { it.id == chapterId }
        require(requestedIndex >= 0) { "Глава больше не принадлежит этой книге" }
        val resolved = mutableMapOf<Int, ResolvedEntry>()
        fun resolve(index: Int): Boolean {
            val chapter = ordered[index]
            if (chapter.scanlator?.trim().orEmpty() != requested.scanlator?.trim().orEmpty()) return false
            val path = chapter.audioPath?.takeIf { it.isNotBlank() } ?: return false
            val file = File(path)
            if (!file.isAbsolute || !file.isFile || !file.canRead() || file.length() == 0L) return false
            if (!file.canonicalFile.toPath().startsWith(container.audioFileManager.audioRoot.canonicalFile.toPath())) return false
            val metadata = MediaMetadata.Builder()
                .setTitle(chapter.name)
                .setArtist(novel.name)
                .setAlbumTitle(novel.name)
                .setIsPlayable(true)
                .setExtras(Bundle().apply { putLong(PlaybackCommands.DURATION, chapter.durationMs.coerceAtLeast(0)) })
                .build()
            resolved[index] = ResolvedEntry(
                chapter,
                MediaItem.Builder()
                    .setMediaId(chapter.id.toString())
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(metadata)
                    .build(),
            )
            return true
        }
        if (!resolve(requestedIndex)) throw IOException("Аудио главы ещё не готово или файл отсутствует")
        var first = requestedIndex
        while (first > 0) {
            currentCoroutineContext().ensureActive()
            if (!resolve(first - 1)) break
            first--
        }
        var last = requestedIndex
        while (last < ordered.lastIndex) {
            currentCoroutineContext().ensureActive()
            if (!resolve(last + 1)) break
            last++
        }
        val chapter = ordered[requestedIndex]
        val position = if (isCompleted(chapter)) 0L else chapter.positionMs.coerceAtLeast(0)
        return ResolvedPlaylist((first..last).map { resolved.getValue(it) }, requestedIndex - first, position)
    }

    private fun isCompleted(chapter: ChapterEntity): Boolean =
        chapter.durationMs > 0 && chapter.positionMs >= chapter.durationMs

    private fun restartCompletedChapter() {
        val id = currentChapterId() ?: return
        if (id !in completed && exoPlayer.playbackState != Player.STATE_ENDED) return
        completed.remove(id)
        resumePolicy.onChapterTransition()
        exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, 0)
        saveCurrent()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (replacingPlaylist) return
            if (!playWhenReady && currentChapterId() != null && exoPlayer.playbackState != Player.STATE_ENDED &&
                reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                resumePolicy.onPaused()
            }
            saveCurrent()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && !replacingPlaylist) saveCurrent()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (replacingPlaylist) return
            if (playbackState == Player.STATE_ENDED) {
                currentChapterId()?.let { id ->
                    completed.add(id)
                    saveProgress(id, currentDuration().coerceAtLeast(exoPlayer.currentPosition), true)
                }
                resumePolicy.onChapterTransition()
            } else if (playbackState == Player.STATE_IDLE) {
                saveCurrent()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (replacingPlaylist) return
            val oldId = oldPosition.mediaItem?.mediaId?.toLongOrNull()
            val newId = newPosition.mediaItem?.mediaId?.toLongOrNull()
            if (oldId != null && oldId != newId) {
                val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                if (finished) completed.add(oldId)
                val endPosition = if (finished && oldPosition.contentPositionMs <= 0) {
                    chapters[oldId]?.durationMs ?: 0L
                } else oldPosition.contentPositionMs
                saveProgress(oldId, endPosition, finished)
            }
            saveCurrent()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull()
            PlaybackAccess.currentChapterId = id
            session?.setSessionActivity(sessionActivity(id))
            if (replacingPlaylist) return
            resumePolicy.onChapterTransition()
            completed.remove(id)
            saveCurrent()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            enqueueWrite(WriteRequest.Speed(playbackParameters.speed))
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed", error)
            saveCurrent()
        }
    }

    private fun detachChapters(ids: Set<Long>) {
        if (currentChapterId() in ids) {
            saveCurrent()
            replacingPlaylist = true
            try {
                exoPlayer.pause()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                chapters.clear()
                completed.clear()
                setSleepTimer(0)
            } finally {
                replacingPlaylist = false
            }
        } else {
            val current = exoPlayer.currentMediaItemIndex
            val future = (current + 1 until exoPlayer.mediaItemCount).firstOrNull {
                exoPlayer.getMediaItemAt(it).mediaId.toLongOrNull() in ids
            }
            if (future != null) exoPlayer.removeMediaItems(future, exoPlayer.mediaItemCount)
            val previous = (0 until current).lastOrNull {
                exoPlayer.getMediaItemAt(it).mediaId.toLongOrNull() in ids
            }
            if (previous != null) exoPlayer.removeMediaItems(0, previous + 1)
            ids.forEach { chapters.remove(it); completed.remove(it) }
        }
    }

    private fun currentChapterId(): Long? = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull()

    private fun currentDuration(): Long = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: chapters[currentChapterId()]?.durationMs?.coerceAtLeast(0) ?: 0L

    private fun saveCurrent() {
        if (replacingPlaylist || destroyed) return
        val id = currentChapterId() ?: return
        saveProgress(id, exoPlayer.currentPosition, id in completed)
    }

    private fun saveProgress(chapterId: Long, positionMs: Long, finished: Boolean) {
        val chapter = chapters[chapterId] ?: return
        val position = positionMs.coerceAtLeast(0)
        chapters[chapterId] = chapter.copy(positionMs = position, isRead = chapter.isRead || finished)
        enqueueWrite(WriteRequest.Progress(chapterId, chapter.novelId, chapter.audioPath, position, finished, System.currentTimeMillis()))
    }

    private fun enqueueWrite(request: WriteRequest) {
        val previous = writeTail
        val next = container.appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                when (request) {
                    is WriteRequest.Progress -> {
                        container.database.withTransaction {
                            val saved = container.chapterDao.savePlayback(request.chapterId, request.audioPath, request.positionMs, request.finished)
                            if (saved > 0) container.novelDao.updateLastRead(request.novelId, request.chapterId, request.timeMs)
                        }
                    }
                    is WriteRequest.Speed -> {
                        val saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                            .putFloat(SPEED, request.speed).commit()
                        if (!saved) throw IOException("Не удалось сохранить скорость воспроизведения")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    reportError("Не удалось сохранить состояние плеера", error)
                }
            }
        }
        writeTail = next
        next.start()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        sleepDeadlineMs = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
        publishExtras()
        if (sleepDeadlineMs == 0L) return
        sleepJob = serviceScope.launch {
            while (isActive) {
                val remaining = sleepDeadlineMs - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                delay(remaining.coerceAtMost(1_000L))
            }
            sessionPlayer.pause()
            sleepDeadlineMs = 0
            publishExtras()
        }
    }

    private fun publishExtras() {
        if (destroyed) return
        session?.setSessionExtras(Bundle().apply {
            putLong(PlaybackCommands.SLEEP_DEADLINE, sleepDeadlineMs)
            serviceError?.let { putString(PlaybackCommands.ERROR, it) }
        })
    }

    private fun reportError(message: String, cause: Exception) {
        Log.e(TAG, message, cause)
        if (!destroyed) {
            serviceError = message
            publishExtras()
        }
    }

    private fun sessionActivity(chapterId: Long?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (chapterId != null) intent.putExtra(PlaybackCommands.CHAPTER_ID, chapterId)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        if (::exoPlayer.isInitialized) saveCurrent()
        destroyed = true
        PlaybackAccess.detachHandler = null
        PlaybackAccess.currentChapterId = null
        serviceScope.cancel()
        if (::exoPlayer.isInitialized) exoPlayer.removeListener(playerListener)
        session?.release()
        session = null
        if (::exoPlayer.isInitialized) exoPlayer.release()
        super.onDestroy()
    }

    private data class ResolvedEntry(val chapter: ChapterEntity, val item: MediaItem)

    private data class ResolvedPlaylist(val entries: List<ResolvedEntry>, val startIndex: Int, val positionMs: Long)

    private sealed interface WriteRequest {
        data class Progress(
            val chapterId: Long,
            val novelId: Long,
            val audioPath: String?,
            val positionMs: Long,
            val finished: Boolean,
            val timeMs: Long,
        ) : WriteRequest
        data class Speed(val speed: Float) : WriteRequest
    }

    private companion object {
        // Main-thread enqueueing also serializes final saves across service recreation.
        var writeTail: Job? = null
        const val TAG = "PlaybackService"
        const val PREFERENCES = "playback"
        const val SPEED = "speed"
    }
}
