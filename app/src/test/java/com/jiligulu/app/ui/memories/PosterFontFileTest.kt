package com.jiligulu.app.ui.memories

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PosterFontFileTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun incompletePreviousExtractionIsReplacedOnceAndCompleteFontIsReused() {
        val target = File(temporary.root, "memory-poster-noto.ttf").apply { writeText("partial") }
        val bytes = "synthetic complete font resource".toByteArray()
        var reads = 0
        val restored = ensurePosterFontFile(temporary.root, bytes.size.toLong()) { reads++; ByteArrayInputStream(bytes) }
        assertEquals(target, restored)
        assertArrayEquals(bytes, target.readBytes())
        ensurePosterFontFile(temporary.root, bytes.size.toLong()) { reads++; error("Complete cache must not be extracted again") }
        assertEquals(1, reads)
        assertEquals(listOf(target.name), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun failedOrShortExtractionDoesNotOverwriteThePreviousFileAndRemovesItsPartial() {
        val target = File(temporary.root, "memory-poster-noto.ttf").apply { writeText("previous font") }
        val previous = target.readBytes()
        assertTrue(runCatching { ensurePosterFontFile(temporary.root, 100) { throw IOException("Synthetic read failure") } }.isFailure)
        assertArrayEquals(previous, target.readBytes())
        assertTrue(runCatching { ensurePosterFontFile(temporary.root, 100) { ByteArrayInputStream("short".toByteArray()) } }.isFailure)
        assertArrayEquals(previous, target.readBytes())
        assertEquals(listOf(target.name), temporary.root.listFiles()!!.map { it.name })
    }
}
