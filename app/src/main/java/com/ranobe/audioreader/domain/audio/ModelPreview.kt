package com.ranobe.audioreader.domain.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.ranobe.audioreader.domain.text.StressDictionary
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelPreview(context: Context, private val manager: SileroModelManager, private val dictionary: StressDictionary) : Closeable {
    data class State(val busy: Boolean = false, val playing: Boolean = false, val error: String? = null)

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var receiverRegistered = false
    private var generation = 0L
    private var closed = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    @MainThread
    fun play(modelId: String, speaker: String, text: String, sampleRate: Int, bitrate: Int) {
        check(!closed) { "Прослушивание закрыто" }
        stop()
        val token = generation
        mutableState.value = State(busy = true)
        job = scope.launch {
            var temporary: File? = null
            try {
                require(text.isNotBlank() && text.length <= 500) { "Введите от 1 до 500 символов" }
                require(sampleRate in setOf(24000, 48000)) { "Нужны 24 или 48 кГц" }
                require(bitrate in setOf(32000, 48000, 64000, 96000)) { "Некорректный битрейт" }
                withContext(Dispatchers.IO) {
                    val snapshot = dictionary.snapshot()
                    val preparedText = snapshot.applyReplacements(text)
                    require(preparedText.isNotBlank() && preparedText.length <= 500) { "После замен нужно не более 500 символов" }
                    val samples = AudioWorkCoordinator.mutex.withLock {
                        currentCoroutineContext().ensureActive()
                        check(manager.isInstalled(modelId)) { "Пак недоступен. Скачайте его заново" }
                        val engine = SileroTtsEngine(context)
                        try {
                            engine.load(manager.modelDir(modelId), sampleRate)
                            currentCoroutineContext().ensureActive()
                            engine.synthesizeChunk(preparedText, speaker, snapshot.userDict)
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { engine.release() }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    check(samples.isNotEmpty()) { "В тексте нет произносимых символов" }
                    val file = File.createTempFile("voice-preview-", ".m4a", context.cacheDir)
                    temporary = file
                    PcmToM4aEncoder(sampleRate, file, bitrate).use { encoder ->
                        encoder.write(samples)
                        encoder.finish()
                    }
                }
                currentCoroutineContext().ensureActive()
                playFile(checkNotNull(temporary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == token) mutableState.value = State(error = e.message ?: "Не удалось проверить голос")
            } catch (e: LinkageError) {
                if (generation == token) mutableState.value = State(error = "Не удалось загрузить библиотеку синтеза")
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (generation == token) {
                        releasePlayback()
                        mutableState.value = mutableState.value.copy(busy = false, playing = false)
                    }
                }
                withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
            }
        }
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        try {
            val token = generation
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (generation == token && change != AudioManager.AUDIOFOCUS_GAIN) stop()
                }, Handler(Looper.getMainLooper()))
                .build()
            focus = request
            check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Звук занят другим приложением"
            }
            ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setOnPreparedListener {
                if (continuation.isActive) {
                    try {
                        it.start()
                        mutableState.value = State(playing = true)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }
            media.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
            media.setOnErrorListener { _, what, extra ->
                if (continuation.isActive) continuation.resumeWithException(IOException("Ошибка воспроизведения ($what/$extra)"))
                true
            }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    @MainThread
    fun stop() {
        generation++
        job?.cancel()
        job = null
        releasePlayback()
        mutableState.value = State()
    }

    private fun releasePlayback() {
        player?.let { media ->
            media.setOnPreparedListener(null)
            media.setOnCompletionListener(null)
            media.setOnErrorListener(null)
            runCatching { media.release() }
        }
        player = null
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            receiverRegistered = false
        }
    }

    @MainThread
    override fun close() {
        closed = true
        stop()
        scope.cancel()
    }
}
