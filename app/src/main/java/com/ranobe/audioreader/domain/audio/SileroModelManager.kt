package com.ranobe.audioreader.domain.audio

import android.content.Context
import com.ranobe.audioreader.tts.Symbols
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class CatalogEntry(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val license: String,
)

data class InstalledModel(
    val id: String,
    val title: String,
    val dir: File,
    val sizeBytes: Long,
    val speakers: Map<String, Int>,
    val types: Boolean,
    val lang: String,
    val license: String? = null,
    val source: String? = null,
)

class SileroModelManager(context: Context, private val client: OkHttpClient) {
    private val appContext = context.applicationContext
    val modelsRoot: File = File(appContext.filesDir, "tts_models").canonicalFile.apply {
        if (!isDirectory && !mkdirs()) throw IOException("Не удалось создать каталог моделей")
    }
    private val archive = ModelPackArchive()
    private val mutableModels = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = mutableModels.asStateFlow()

    val catalog = listOf(
        CatalogEntry(
            id = "ru",
            title = "Русские голоса (Silero v5_5_ru)",
            description = "5 голосов: aidar, baya, kseniya, xenia, eugene. Автоударения и омографы.",
            url = "$PACKS_BASE/ruvoice-pack-ru.zip",
            license = "CC BY-NC-SA 4.0",
        ),
        CatalogEntry(
            id = "cis_ru",
            title = "Дополнительные русские голоса (MIT)",
            description = "29 голосов модели v5_cis_base_nostress. Свободная лицензия MIT.",
            url = "$PACKS_BASE/ruvoice-pack-cis_ru.zip",
            license = "MIT",
        ),
    )

    fun modelDir(id: String): File = ModelPackArchive.modelDirectory(modelsRoot, id)

    fun isInstalled(id: String): Boolean {
        val directory = modelDir(id)
        return runCatching { readModel(directory) }.isSuccess
    }

    fun installed(): List<InstalledModel> = modelsRoot.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { directory -> runCatching { readModel(modelDir(directory.name)) }.getOrNull() }
        .sortedBy { it.id }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        AudioWorkCoordinator.mutex.withLock { mutableModels.value = installed() }
    }

    private fun readModel(directory: File): InstalledModel {
        val metadata = archive.validateDirectory(directory, directory.name)
        validateSymbols(metadata)
        return metadata.installed(directory)
    }

    private fun validateSymbols(metadata: ModelPackArchive.Metadata) {
        try {
            val symbols = Symbols.fromJson(JSONObject(metadata.json))
            symbols.allowed
            symbols.sequence("")
        } catch (e: Exception) {
            throw IOException("Некорректная таблица символов в pack.json", e)
        }
    }

    private fun ModelPackArchive.Metadata.installed(directory: File) = InstalledModel(
        id, title, directory, directory.listFiles().orEmpty().sumOf { it.length() },
        speakers, types, lang, license, source,
    )

    suspend fun download(id: String, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val destination = modelDir(id)
            val entry = catalog.firstOrNull { it.id == id } ?: throw IOException("Неизвестная модель: $id")
            if (destination.exists()) throw IOException("Сначала удалите установленный пак")
            val zip = fetch(entry.url, onProgress)
            try {
                currentCoroutineContext().ensureActive()
                onProgress(0, -1)
                zip.inputStream().use { input -> install(input, id) }
            } finally {
                zip.delete()
            }
        }

    suspend fun importZip(input: InputStream): File = importZip(input) { _, _ -> }

    suspend fun importZip(input: InputStream, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) { input.use { install(it, null, onProgress) } }

    private suspend fun install(
        input: InputStream,
        expectedId: String?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val coroutine = currentCoroutineContext()
        archive.prepare(input, modelsRoot, expectedId, onProgress) { coroutine.ensureActive() }.use { prepared ->
            validateSymbols(prepared.metadata)
            return AudioWorkCoordinator.mutex.withLock {
                coroutine.ensureActive()
                withContext(NonCancellable) {
                    val destination = prepared.commit(modelsRoot)
                    mutableModels.value = installed()
                    destination
                }
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val destination = modelDir(id)
        var removed: File? = null
        try {
            AudioWorkCoordinator.mutex.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (destination.exists()) {
                        val trash = File(modelsRoot, ".removed-${UUID.randomUUID()}")
                        if (!destination.renameTo(trash)) throw IOException("Не удалось удалить пак")
                        removed = trash
                    }
                    mutableModels.value = installed()
                }
            }
        } finally {
            removed?.let {
                if (!it.deleteRecursively()) throw IOException("Пак отключён, но не все его файлы удалось удалить")
            }
        }
    }

    private suspend fun fetch(url: String, onProgress: (Long, Long) -> Unit): File =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    var temporary: File? = null
                    var handedOff = false
                    try {
                        response.use {
                            if (!response.isSuccessful) throw IOException("Ошибка скачивания: HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Пустой ответ сервера")
                            val total = body.contentLength()
                            val limit = ModelPackArchive.Limits().zipBytes
                            if (total > limit) throw IOException("ZIP превышает 1 ГиБ")
                            if (!continuation.isActive) return
                            val file = File.createTempFile(".network-", ".zip", modelsRoot)
                            temporary = file
                            body.byteStream().use { input ->
                                file.outputStream().use { output ->
                                    var done = 0L
                                    val buffer = ByteArray(64 * 1024)
                                    onProgress(0, total)
                                    while (true) {
                                        if (!continuation.isActive) return
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (read == 0) continue
                                        if (read > limit - done) throw IOException("ZIP превышает 1 ГиБ")
                                        output.write(buffer, 0, read)
                                        done += read
                                        onProgress(done, total)
                                    }
                                    if (total >= 0 && done != total) throw IOException("Скачивание прервано сервером")
                                }
                            }
                            handedOff = true
                            continuation.resume(file) { _, cancelledFile, _ -> cancelledFile.delete() }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally {
                        if (!handedOff) temporary?.delete()
                    }
                }
            })
        }

    private companion object {
        const val PACKS_BASE = "https://github.com/kost-t-human/ruvoice-tts/releases/download/packs"
    }
}
