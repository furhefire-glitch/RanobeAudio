package com.ranobe.audioreader.domain.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPackArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val archive = ModelPackArchive()

    private fun metadata(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): String {
        val base = Json.parseToJsonElement("""{
            "format":2,"id":"ru","title":"Русский пак","lang":"ru",
            "speakers":{"voice":0},"types":false,
            "symbols":"_~|аб ","symbol_to_id":{"_":0,"~":1,"|":2,"а":3,"б":4," ":5},
            "sos":"|","eos":"~","alphabet":"аб",
            "license":"MIT","source":"https://example.org/model"
        }""") as JsonObject
        return JsonObject(base + fields.toMap()).toString()
    }

    private fun files(json: String = metadata()): List<Pair<String, ByteArray>> = listOf(
        "pack.json" to json.toByteArray(),
        "tts_mel.ptl" to byteArrayOf(1, 2, 3),
        "head.ptl" to byteArrayOf(4, 5),
        "backbone.pte" to byteArrayOf(6, 7),
    )

    private fun zip(entries: List<Pair<String, ByteArray>> = files(), stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun root(): File = temporary.newFolder().canonicalFile

    @Test fun validPackIsInvisibleUntilAtomicCommit() {
        val root = root()
        var progressCalls = 0
        archive.prepare(zip().inputStream(), root, "ru", { _, _ -> progressCalls++ }).use { prepared ->
            assertFalse(File(root, "ru").exists())
            assertEquals("MIT", prepared.metadata.license)
            assertEquals("https://example.org/model", prepared.metadata.source)
            assertEquals(mapOf("voice" to 0), prepared.metadata.speakers)
            val destination = prepared.commit(root)
            assertEquals("ru", destination.name)
            assertEquals("ru", archive.validateDirectory(destination, "ru").id)
        }
        assertEquals(listOf("ru"), root.list()!!.toList())
        assertTrue(progressCalls > 0)
    }

    @Test fun acceptsFormatThreeAndCisSymbolsWithExtraServiceSymbols() {
        val json = metadata("format" to JsonPrimitive(3), "id" to JsonPrimitive("cis_ru"),
            "symbols" to JsonPrimitive("|аб "))
        archive.prepare(zip(files(json)).inputStream(), root(), "cis_ru").use {
            assertEquals("cis_ru", it.metadata.id)
        }
    }

    @Test fun legacyMetadataMayOmitLanguageAndLicense() {
        val data = Json.parseToJsonElement(metadata()) as JsonObject
        val json = JsonObject(data - setOf("lang", "license", "source")).toString()
        val parsed = archive.parseMetadata(json)
        assertEquals("ru", parsed.lang)
        assertEquals(null, parsed.license)
        assertEquals(null, parsed.source)
    }

    @Test fun optionalFlatAssetsAndLegalFilesAreAccepted() {
        val extras = listOf("accentor.ptl", "homo.ptl", "morph.bin", "stress.bin", "stress.txt", "LICENSE", "NOTICE.txt")
            .map { it to byteArrayOf(1) }
        archive.prepare(zip(files() + extras).inputStream(), root()).use { prepared ->
            extras.forEach { assertTrue(File(prepared.directory, it.first).isFile) }
        }
    }

    @Test fun rejectsEveryMissingRequiredFile() {
        ModelPackArchive.REQUIRED.forEach { name ->
            val root = root()
            assertThrows(IOException::class.java) {
                archive.prepare(zip(files().filterNot { it.first == name }).inputStream(), root)
            }
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun rejectsEmptyRequiredFiles() {
        ModelPackArchive.REQUIRED.forEach { name ->
            assertThrows(IOException::class.java) {
                archive.prepare(zip(files().map { if (it.first == name) name to byteArrayOf() else it }).inputStream(), root())
            }
        }
    }

    @Test fun rejectsTraversalAbsoluteNestedAndUnknownNames() {
        listOf("../pack.json", "../escape", "/pack.json", "C:/pack.json", "C:\\pack.json", "..\\pack.json",
            "nested/pack.json", "./pack.json", "folder/", "tts_mel.pt", "arbitrary.onnx", "pack.json ", "PACK.JSON",
            "LICENSE/other", "pack.json:stream").forEach { name ->
            val root = root()
            assertThrows("Must reject $name", IOException::class.java) {
                archive.prepare(zip(files() + (name to byteArrayOf(9))).inputStream(), root)
            }
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun rejectsDuplicateZipEntries() {
        val bytes = zip(files() + ("homo.ptl" to byteArrayOf(1)))
        replaceAscii(bytes, "homo.ptl", "head.ptl")
        val error = assertThrows(IOException::class.java) { archive.prepare(bytes.inputStream(), root()) }
        assertTrue(error.message!!.contains("Повтор"))
    }

    @Test fun rejectsCaseInsensitiveLegalDuplicates() {
        assertThrows(IOException::class.java) {
            archive.prepare(zip(files() + listOf("LICENSE" to byteArrayOf(1), "license" to byteArrayOf(2))).inputStream(), root())
        }
    }

    @Test fun checksMetadataAndFileAndTotalAndZipLimits() {
        val jsonSize = metadata().toByteArray().size.toLong()
        val cases = listOf(
            ModelPackArchive.Limits(metadataBytes = jsonSize - 1),
            ModelPackArchive.Limits(fileBytes = jsonSize - 1),
            ModelPackArchive.Limits(totalBytes = jsonSize + 6),
            ModelPackArchive.Limits(zipBytes = 20),
        )
        cases.forEach { limits ->
            val root = root()
            assertThrows(IOException::class.java) { ModelPackArchive(limits).prepare(zip().inputStream(), root) }
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun allowsExactSizeBounds() {
        val files = files()
        val compressed = zip(files)
        val total = files.sumOf { it.second.size.toLong() }
        val size = files.first().second.size.toLong()
        ModelPackArchive(ModelPackArchive.Limits(size, total, size, compressed.size.toLong()))
            .prepare(compressed.inputStream(), root()).use { assertEquals("ru", it.metadata.id) }
    }

    @Test fun checksActualInflatedSizeEvenWhenDirectoryLies() {
        val compressed = zip(files() + ("stress.txt" to ByteArray(5000)))
        // The central-directory uncompressed size of stress.txt is forged to one byte.
        val name = "stress.txt".toByteArray()
        for (i in 0 until compressed.size - 46 - name.size) {
            if (compressed[i] == 0x50.toByte() && compressed[i + 1] == 0x4b.toByte() &&
                compressed[i + 2] == 1.toByte() && compressed[i + 3] == 2.toByte() &&
                name.indices.all { compressed[i + 46 + it] == name[it] }) {
                compressed[i + 24] = 1
                for (offset in 25..27) compressed[i + offset] = 0
            }
        }
        val root = root()
        assertThrows(IOException::class.java) {
            ModelPackArchive(ModelPackArchive.Limits(fileBytes = 1024)).prepare(compressed.inputStream(), root)
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun checksActualTotalSizeEvenWhenDirectoryLies() {
        val compressed = zip(files() + ("stress.txt" to ByteArray(5000)))
        val name = "stress.txt".toByteArray()
        for (i in 0 until compressed.size - 46 - name.size) {
            if (compressed[i] == 0x50.toByte() && compressed[i + 1] == 0x4b.toByte() &&
                compressed[i + 2] == 1.toByte() && compressed[i + 3] == 2.toByte() &&
                name.indices.all { compressed[i + 46 + it] == name[it] }) {
                compressed[i + 24] = 1
                for (offset in 25..27) compressed[i + offset] = 0
            }
        }
        assertThrows(IOException::class.java) {
            ModelPackArchive(ModelPackArchive.Limits(totalBytes = 1024)).prepare(compressed.inputStream(), root())
        }
    }

    @Test fun rejectsCorruptCrcAndTruncatedZip() {
        val root = root()
        val bytes = zip(stored = true)
        val marker = byteArrayOf(1, 2, 3)
        val index = (0 until bytes.size - marker.size).first { i -> marker.indices.all { bytes[i + it] == marker[it] } }
        bytes[index] = 9
        assertThrows(IOException::class.java) { archive.prepare(bytes.inputStream(), root) }
        val valid = zip()
        assertThrows(IOException::class.java) { archive.prepare(valid.copyOf(valid.size - 30).inputStream(), root) }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun rejectsWrongCatalogIdAndInvalidMetadataWithoutTouchingInstalledPack() {
        val root = root()
        archive.prepare(zip().inputStream(), root).use { it.commit(root) }
        val original = File(root, "ru/pack.json").readBytes()
        assertThrows(IOException::class.java) { archive.prepare(zip().inputStream(), root, "cis_ru") }
        assertTrue(original.contentEquals(File(root, "ru/pack.json").readBytes()))
        assertEquals(listOf("ru"), root.list()!!.toList())
    }

    @Test fun refusesOverwriteEvenIfDestinationAppearsAfterPreparation() {
        val root = root()
        archive.prepare(zip().inputStream(), root).use { first ->
            archive.prepare(zip().inputStream(), root).use { second ->
                first.commit(root)
                val error = assertThrows(IOException::class.java) { second.commit(root) }
                assertEquals("Сначала удалите установленный пак", error.message)
            }
        }
        assertEquals("ru", archive.validateDirectory(File(root, "ru")).id)
        assertEquals(listOf("ru"), root.list()!!.toList())
    }

    @Test fun refusesOverwriteOfIncompleteExistingDirectory() {
        val root = root()
        File(root, "ru").mkdir()
        File(root, "ru/keep").writeText("untouched")
        archive.prepare(zip().inputStream(), root).use {
            assertThrows(IOException::class.java) { it.commit(root) }
        }
        assertEquals("untouched", File(root, "ru/keep").readText())
    }

    @Test fun failedCommitDoesNotCopyPartialDestination() {
        val root = root()
        archive.prepare(zip().inputStream(), root).use {
            it.directory.deleteRecursively()
            assertThrows(IOException::class.java) { it.commit(root) }
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun cancellationDuringCopyAndExtractionRemovesOnlyStaging() {
        for (cancelDuringExtraction in listOf(false, true)) {
            val root = root()
            archive.prepare(zip().inputStream(), root).use { it.commit(root) }
            var cancelled = false
            assertThrows(CancellationException::class.java) {
                archive.prepare(zip().inputStream(), root, onProgress = { done, total ->
                    if ((total >= 0) == cancelDuringExtraction && done > 0) cancelled = true
                }, checkCancelled = { if (cancelled) throw CancellationException("cancel") })
            }
            assertEquals(listOf("ru"), root.list()!!.toList())
            assertNotNull(archive.validateDirectory(File(root, "ru")))
        }
    }

    @Test fun inputFailureClosesStreamAndKeepsInstalledFiles() {
        val root = root()
        archive.prepare(zip().inputStream(), root).use { it.commit(root) }
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { archive.prepare(broken, root) }
        assertTrue(closed)
        assertEquals(listOf("ru"), root.list()!!.toList())
    }

    @Test fun cancellationBeforeCommitLeavesNoNewModel() {
        val root = root()
        assertThrows(CancellationException::class.java) {
            archive.prepare(zip().inputStream(), root).use { throw CancellationException() }
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun rejectsInvalidIdsLanguagesFormatsVoicesAndSymbols() {
        val bad = listOf(
            "id" to JsonPrimitive("../ru"), "id" to JsonPrimitive("RU"), "id" to JsonPrimitive(""),
            "id" to JsonPrimitive("r".repeat(41)), "lang" to JsonPrimitive("uk"),
            "format" to JsonPrimitive(1), "format" to JsonPrimitive(4), "format" to JsonPrimitive("2"),
            "speakers" to JsonObject(emptyMap()), "speakers" to JsonObject(mapOf("" to JsonPrimitive(0))),
            "speakers" to JsonObject(mapOf("voice" to JsonPrimitive(-1))),
            "speakers" to JsonObject(mapOf("voice" to JsonPrimitive(0.5))),
            "speakers" to JsonObject(mapOf("voice" to JsonPrimitive("0"))),
            "sos" to JsonPrimitive(""), "eos" to JsonPrimitive("!"),
            "symbols" to JsonPrimitive("ab"), "symbols" to JsonPrimitive("_~|аа"),
            "alphabet" to JsonPrimitive(""), "alphabet" to JsonPrimitive("z"),
            "symbol_to_id" to JsonObject(emptyMap()),
            "symbol_to_id" to JsonObject(mapOf("long" to JsonPrimitive(0))),
            "types" to JsonPrimitive("true"),
        )
        bad.forEach { field ->
            assertThrows("Must reject $field", IOException::class.java) { archive.parseMetadata(metadata(field)) }
        }
        assertThrows(IOException::class.java) { archive.parseMetadata("not json") }
        assertThrows(IOException::class.java) { archive.parseMetadata("[]") }
    }

    @Test fun modelDirectoryRejectsUnsafeIds() {
        val root = root()
        listOf("..", ".", "a/b", "a\\b", "A", "", "a-b", "x".repeat(41)).forEach { id ->
            assertThrows(IOException::class.java) { ModelPackArchive.modelDirectory(root, id) }
        }
        assertEquals(File(root, "cis_ru"), ModelPackArchive.modelDirectory(root, "cis_ru"))
        assertEquals(File(root, "x".repeat(40)), ModelPackArchive.modelDirectory(root, "x".repeat(40)))
    }

    @Test fun defaultLimitsMatchContract() {
        val limits = ModelPackArchive.Limits()
        assertEquals(1024L * 1024, limits.metadataBytes)
        assertEquals(512L * 1024 * 1024, limits.fileBytes)
        assertEquals(1024L * 1024 * 1024, limits.totalBytes)
        assertEquals(limits.totalBytes, limits.zipBytes)
    }

    private fun replaceAscii(bytes: ByteArray, from: String, to: String) {
        val old = from.toByteArray()
        val replacement = to.toByteArray()
        require(old.size == replacement.size)
        for (i in 0..bytes.size - old.size) {
            if (old.indices.all { bytes[i + it] == old[it] }) replacement.copyInto(bytes, i)
        }
    }
}
