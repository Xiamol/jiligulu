package com.jiligulu.app.ui.memories

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publishes a complete fallback font; failed extraction leaves the old file untouched. */
internal fun ensurePosterFontFile(directory: File, expectedSize: Long, source: () -> InputStream): File {
    val target = File(directory, "memory-poster-noto.ttf")
    if (target.isFile && expectedSize > 0 && target.length() == expectedSize) return target
    directory.mkdirs()
    val temporary = File.createTempFile("memory-font-", ".tmp", directory)
    try {
        val copied = source().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        check(copied > 0 && temporary.length() == copied && (expectedSize <= 0 || copied == expectedSize))
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return target
    } finally { temporary.delete() }
}
