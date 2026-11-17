package com.ranobe.audioreader.domain.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal class ModelPackArchive(private val limits: Limits = Limits()) {
    data class Limits(
        val metadataBytes: Long = 1024L * 1024,
        val totalBytes: Long = 1024L * 1024 * 1024,
        val fileBytes: Long = 512L * 1024 * 1024,
        val zipBytes: Long = 1024L * 1024 * 1024,
    ) {
        init {
            require(metadataBytes > 0 && totalBytes > 0 && fileBytes > 0 && zipBytes > 0)
        }
    }

    data class Metadata(
        val id: String,
        val title: String,
        val speakers: Map<String, Int>,
        val types: Boolean,
        val lang: String,
        val license: String?,
        val source: String?,
        val json: String,
    )

    class Prepared internal constructor(
        private val staging: File,
        val directory: File,
        val metadata: Metadata,
    ) : Closeable {
        fun commit(root: File): File {
            val destination = modelDirectory(root, metadata.id)
            if (destination.exists()) throw IOException("Сначала удалите установленный пак")
            if (!directory.renameTo(destination)) throw IOException("Не удалось сохранить пак атомарно")
            return destination
        }

        override fun close() {
            staging.deleteRecursively()
        }
    }

    fun prepare(
        input: InputStream,
        root: File,
        expectedId: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Prepared {
        expectedId?.let(::validateId)
        val staging = File(root, ".install-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Не удалось создать временный каталог")
        try {
            val zip = File(staging, "archive.zip")
            input.use { source ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    onProgress(0, -1)
                    while (true) {
                        checkCancelled()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > limits.zipBytes - done) throw IOException("ZIP превышает допустимый размер")
                        output.write(buffer, 0, count)
                        done += count
                        onProgress(done, -1)
                    }
                }
            }
            val payload = File(staging, "payload")
            if (!payload.mkdir()) throw IOException("Не удалось создать временный каталог")
            extract(zip, payload, onProgress, checkCancelled)
            checkCancelled()
            val metadata = validateDirectory(payload, expectedId)
            return Prepared(staging, payload, metadata)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun extract(zip: File, destination: File, progress: (Long, Long) -> Unit, checkCancelled: () -> Unit) {
        ZipFile(zip).use { archive ->
            val seen = HashSet<String>()
            val entries = ArrayList<java.util.zip.ZipEntry>()
            var declaredTotal = 0L
            val iterator = archive.entries()
            while (iterator.hasMoreElements()) {
                checkCancelled()
                val entry = iterator.nextElement()
                val name = entry.name
                if (entry.isDirectory || !allowed(name)) throw IOException("Недопустимый файл в ZIP: ${name.take(80)}")
                if (!seen.add(name.lowercase(Locale.ROOT))) throw IOException("Повтор файла в ZIP: $name")
                val limit = fileLimit(name)
                if (entry.size < 0 || entry.size > limit || entry.size > limits.totalBytes - declaredTotal) {
                    throw IOException("Превышен размер файлов пака")
                }
                declaredTotal += entry.size
                entries += entry
            }
            if (!seen.containsAll(REQUIRED)) throw IOException("Нужны pack.json, tts_mel.ptl, head.ptl и backbone.pte")
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            progress(0, declaredTotal)
            entries.forEach { entry ->
                val crc = CRC32()
                var count = 0L
                archive.getInputStream(entry).use { input ->
                    File(destination, entry.name).outputStream().use { output ->
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            if (read > fileLimit(entry.name) - count || read > limits.totalBytes - total) {
                                throw IOException("Превышен размер распакованных файлов")
                            }
                            output.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            count += read
                            total += read
                            progress(total, declaredTotal)
                        }
                    }
                }
                if (count != entry.size || crc.value != entry.crc) throw IOException("Повреждён файл ${entry.name}")
                if (entry.name in REQUIRED && count == 0L) throw IOException("Пустой файл ${entry.name}")
            }
        }
    }

    fun validateDirectory(directory: File, expectedId: String? = null): Metadata {
        val files = directory.listFiles() ?: throw IOException("Каталог пака недоступен")
        var total = 0L
        files.forEach { file ->
            if (!allowed(file.name) || !file.isFile || file.canonicalFile != File(directory.canonicalFile, file.name)) {
                throw IOException("Недопустимый файл пака")
            }
            val size = file.length()
            if (size > fileLimit(file.name) || size > limits.totalBytes - total) throw IOException("Превышен размер пака")
            total += size
        }
        REQUIRED.forEach { name ->
            if (files.none { it.name == name && it.length() > 0 }) throw IOException("В паке отсутствует $name")
        }
        val bytes = File(directory, "pack.json").inputStream().use { input ->
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (data.size().toLong() + read > limits.metadataBytes) throw IOException("pack.json слишком большой")
                data.write(buffer, 0, read)
            }
            data.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return parseMetadata(text, expectedId)
    }

    fun parseMetadata(text: String, expectedId: String? = null): Metadata {
        if (text.toByteArray(Charsets.UTF_8).size > limits.metadataBytes) throw IOException("pack.json слишком большой")
        try {
            val json = Json.parseToJsonElement(text) as? JsonObject ?: error("Нужен объект JSON")
            fun string(key: String): String {
                val value = json[key] as? JsonPrimitive
                require(value != null && value.isString) { "Некорректное поле $key" }
                return value.content
            }
            fun optional(key: String): String? = if (json[key] == null || json[key] == JsonNull) null else string(key)
            fun integer(value: kotlinx.serialization.json.JsonElement?): Int {
                val primitive = value as? JsonPrimitive
                require(primitive != null && !primitive.isString) { "Нужно целое число" }
                return requireNotNull(primitive.intOrNull) { "Нужно целое число" }
            }
            require(integer(json["format"]) in 2..3) { "Поддерживаются форматы пака 2–3" }
            val id = string("id")
            validateId(id)
            require(expectedId == null || id == expectedId) { "ID пака не соответствует каталогу: $id" }
            val lang = optional("lang") ?: "ru"
            require(lang == "ru") { "Пока поддерживаются только русские паки (lang ru)" }
            val speakersJson = json["speakers"] as? JsonObject ?: error("Нет голосов")
            require(speakersJson.isNotEmpty()) { "В паке нет голосов" }
            val speakers = speakersJson.mapValues { (name, value) ->
                require(name.isNotBlank()) { "Пустое имя голоса" }
                integer(value).also { require(it >= 0) { "Некорректный ID голоса" } }
            }
            val symbols = string("symbols")
            require(symbols.length >= 3 && symbols.toSet().size == symbols.length) { "Некорректные symbols" }
            val mapping = json["symbol_to_id"] as? JsonObject ?: error("Нет symbol_to_id")
            val ids = mapping.map { (key, value) ->
                require(key.length == 1 && !key[0].isSurrogate()) { "Некорректный символ" }
                integer(value).also { require(it >= 0) { "Некорректный ID символа" } }
            }
            require(ids.toSet().size == ids.size) { "Повтор ID символа" }
            require(symbols.all { it.toString() in mapping }) { "Символ отсутствует в symbol_to_id" }
            listOf("sos", "eos").forEach { key ->
                val symbol = string(key)
                require(symbol.length == 1 && symbol in mapping) { "Некорректный $key" }
            }
            val alphabet = string("alphabet")
            require(alphabet.isNotEmpty() && alphabet.all { it.toString() in mapping }) { "Некорректный alphabet" }
            val types = if (json["types"] == null) false else {
                val value = json["types"] as? JsonPrimitive
                require(value != null && !value.isString) { "Некорректный types" }
                requireNotNull(value.booleanOrNull) { "Некорректный types" }
            }
            return Metadata(id, optional("title")?.takeIf { it.isNotBlank() } ?: id, speakers, types, lang,
                optional("license"), optional("source"), text)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Некорректный pack.json", e)
        } catch (e: IllegalStateException) {
            throw IOException(e.message ?: "Некорректный pack.json", e)
        }
    }

    private fun fileLimit(name: String): Long = if (name == "pack.json" || legalFile(name)) {
        minOf(limits.metadataBytes, limits.fileBytes)
    } else limits.fileBytes

    private fun allowed(name: String): Boolean = name in PAYLOAD || legalFile(name)

    private fun legalFile(name: String): Boolean = name.lowercase(Locale.ROOT) in LEGAL

    companion object {
        val REQUIRED = setOf("pack.json", "tts_mel.ptl", "head.ptl", "backbone.pte")
        private val PAYLOAD = REQUIRED + setOf("accentor.ptl", "homo.ptl", "morph.bin", "stress.bin", "stress.txt")
        private val LEGAL = setOf("license", "license.txt", "license.md", "notice", "notice.txt", "notice.md")
        private val ID = Regex("[a-z0-9_]{1,40}")

        fun validateId(id: String) {
            if (!ID.matches(id)) throw IOException("Недопустимый ID пака")
        }

        fun modelDirectory(root: File, id: String): File {
            validateId(id)
            val directory = File(root, id)
            if (directory.canonicalFile != File(root.canonicalFile, id)) throw IOException("Недопустимый каталог пака")
            return directory
        }
    }
}
