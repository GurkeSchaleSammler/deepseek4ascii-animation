package com.example.demo.`ascii-animation`

import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDate

data class GalleryEntry(val id: String, val title: String, val savedAt: Long)

@Service
class AnimationArchive(@Value("\${ascii.backup-directory:backup}") backupDirectory: String) {
    private val directory = Path.of(backupDirectory)

    fun list(): List<GalleryEntry> = try {
        Files.list(directory).use { files ->
            files.filter(::isAnimationFile)
                .map { file ->
                    val id = file.fileName.toString()
                    val title = Jsoup.parse(file.toFile(), "UTF-8").title()
                    GalleryEntry(id, title.ifBlank { id.substringBeforeLast('.') }, Files.getLastModifiedTime(file).toMillis())
                }
                .sorted(compareByDescending<GalleryEntry> { it.savedAt }.thenBy { it.id })
                .toList()
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (exception: IOException) {
        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Die Galerie konnte nicht geladen werden.", exception)
    }

    fun read(id: String): String {
        if (id.contains('/') || id.contains('\\') || !id.endsWith(".html", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Animation nicht gefunden.")
        }
        val file = directory.resolve(id)
        if (!isAnimationFile(file)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Animation nicht gefunden.")
        }

        return try {
            Files.newInputStream(file, NOFOLLOW_LINKS).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (exception: NoSuchFileException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Animation nicht gefunden.", exception)
        } catch (exception: IOException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Die Animation konnte nicht gelesen werden.", exception)
        }
    }

    fun save(html: String) {
        try {
            Files.createDirectories(directory)
            val temporaryFile = Files.createTempFile(directory, "animation-${LocalDate.now()}-", ".tmp")
            try {
                Files.writeString(temporaryFile, html)
                val backupFile = temporaryFile.resolveSibling(temporaryFile.fileName.toString().removeSuffix(".tmp") + ".html")
                Files.move(temporaryFile, backupFile)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        } catch (exception: IOException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Die Animation konnte nicht gesichert werden.", exception)
        }
    }

    private fun isAnimationFile(file: Path): Boolean =
        file.fileName.toString().endsWith(".html", ignoreCase = true) && Files.isRegularFile(file, NOFOLLOW_LINKS)
}
