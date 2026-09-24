package com.jiligulu.app.ui.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OfflineVoiceTest {
    private fun zip(name: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1, 2, 3)); it.closeEntry() }
    }.toByteArray()

    @Test fun modelExtractionRejectsPathsOutsideItsDirectory() {
        val root = Files.createTempDirectory("gulu-model-test").toFile()
        try {
            OfflineModelFiles.extract(ByteArrayInputStream(zip("${OfflineModelFiles.ROOT}/conf/model.conf")), root)
            assertEquals(3L, root.resolve("${OfflineModelFiles.ROOT}/conf/model.conf").length())
            assertTrue(runCatching {
                OfflineModelFiles.extract(ByteArrayInputStream(zip("${OfflineModelFiles.ROOT}/../../escape")), root)
            }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun partialFilesAreNotAcceptedAsAnInstalledModel() {
        val root = Files.createTempDirectory("gulu-model-test").toFile()
        try {
            assertFalse(OfflineModelFiles.installed(root))
            root.resolve(".verified").writeText(OfflineModelFiles.SHA256)
            assertFalse(OfflineModelFiles.installed(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun ChineseWordSpacesAreRemovedWithoutInventingAmounts() {
        assertEquals("昨天午饭九块五", OfflineSpeechSession.speechText("""{"text":"昨天 午饭 九 块 五"}""", "text"))
        assertEquals("买了iPhone", OfflineSpeechSession.speechText("""{"partial":"买 了iPhone"}""", "partial"))
        assertEquals("", OfflineSpeechSession.speechText("""{"text":""}""", "text"))
    }
}
