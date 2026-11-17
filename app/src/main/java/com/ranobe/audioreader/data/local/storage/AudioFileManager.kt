package com.ranobe.audioreader.data.local.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

class AudioFileManager(context: Context) {
    val audioRoot: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio").canonicalFile.apply {
        if (!isDirectory && !mkdirs()) throw IOException("Не удалось создать каталог аудио")
    }

    private fun novelDir(novelId: Long): File {
        require(novelId > 0)
        return File(audioRoot, novelId.toString()).also {
            requireContained(it)
            require(!Files.isSymbolicLink(it.toPath())) { "Каталог книги не может быть символической ссылкой" }
        }
    }

    fun chapterAudioFile(novelId: Long, chapterId: Long): File {
        require(chapterId > 0)
        val dir = novelDir(novelId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Не удалось создать каталог книги")
        return File(dir, "$chapterId.m4a").also { requireContained(it) }
    }

    fun managedFile(path: String?): File? {
        if (path == null) return null
        val file = File(path)
        require(file.isAbsolute) { "Некорректный путь аудио" }
        requireContained(file)
        require(!file.isDirectory) { "Ожидался аудиофайл, а не каталог" }
        return file
    }

    fun deleteNovelAudio(novelId: Long) {
        val directory = novelDir(novelId)
        if (!directory.exists()) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun removeOrphanedAudio(referencedPaths: Set<String>): Long {
        val references = referencedPaths.mapTo(hashSetOf()) { File(it).canonicalPath }
        val ownedName = Regex("[0-9]+(?:-[a-f0-9-]+)?\\.(?:m4a|partial)")
        var removed = 0L
        audioRoot.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && ownedName.matches(it.name) && it.canonicalPath !in references
        }.forEach {
            requireContained(it)
            val bytes = it.length()
            if (!it.delete()) throw IOException("Не удалось удалить временное аудио: ${it.name}")
            removed += bytes
        }
        return removed
    }

    fun novelAudioBytes(novelId: Long): Long = dirSize(novelDir(novelId))
    fun totalAudioBytes(): Long = dirSize(audioRoot)
    fun freeSpaceBytes(): Long = StatFs(audioRoot.absolutePath).availableBytes

    private fun requireContained(file: File) {
        require(file.canonicalFile.toPath().startsWith(audioRoot.toPath()) && file.canonicalFile != audioRoot) {
            "Аудиофайл находится вне хранилища приложения"
        }
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(audioRoot.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
}
