package com.jiligulu.app.ui.littleworld

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RapfiBundleStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bundleId = "a".repeat(64)
    private val assets = linkedMapOf(
        "config.toml" to "[general]\ndefault_thread_num=1\n".toByteArray(),
        "network.bin.lz4" to ByteArray(140_000) { (it % 251).toByte() },
        "model.bin" to "classical model fixture".toByteArray())
    private val specs get() = assets.map { (name, bytes) -> RapfiBundledFile(name, sha(bytes)) }

    @Test fun onlyAFullyVerifiedBundleIsReturnedAndReusedWithoutRecopying() {
        val opened = ArrayList<String>()
        val store = RapfiBundleStore()
        val directory = requireNotNull(store.prepare(temporary.root, bundleId, specs,
            openAsset = { name -> opened += name; ByteArrayInputStream(assets.getValue(name)) }, shouldStop = { false }))
        assertEquals(specs.map { it.name }, opened)
        assertBundle(directory)
        assertEquals(directory, store.prepare(temporary.root, bundleId, specs,
            openAsset = { error("verified assets must be reused") }, shouldStop = { false }))
        assertEquals(directory, RapfiBundleStore().prepare(temporary.root, bundleId, specs,
            openAsset = { error("a cold start must hash and reuse its complete assets") }, shouldStop = { false }))
        assertBundle(directory)
    }

    @Test fun oneIncorrectAssetNeverPublishesALaunchDirectoryOrItsPartialBytes() {
        val opened = ArrayList<String>()
        val store = RapfiBundleStore()
        val result = store.prepare(temporary.root, bundleId, specs, openAsset = { name ->
            opened += name
            ByteArrayInputStream(if (name == "network.bin.lz4") "wrong weights".toByteArray() else assets.getValue(name))
        }, shouldStop = { false })
        assertNull(result)
        assertEquals(listOf("config.toml", "network.bin.lz4"), opened)
        val directory = File(temporary.root, bundleId)
        assertArrayEquals(assets.getValue("config.toml"), File(directory, "config.toml").readBytes())
        assertFalse(File(directory, "network.bin.lz4").exists())
        assertFalse(File(directory, "model.bin").exists())
        assertNoTemporaryFiles(directory)
        val recovered = requireNotNull(store.prepare(temporary.root, bundleId, specs,
            openAsset = { ByteArrayInputStream(assets.getValue(it)) }, shouldStop = { false }))
        assertBundle(recovered)
    }

    @Test fun changedExistingAssetIsRepairedFromVerifiedBytes() {
        val store = RapfiBundleStore()
        val directory = requireNotNull(store.prepare(temporary.root, bundleId, specs,
            openAsset = { ByteArrayInputStream(assets.getValue(it)) }, shouldStop = { false }))
        File(directory, "network.bin.lz4").writeText("corrupted weights with a different length")
        val opened = ArrayList<String>()
        assertEquals(directory, store.prepare(temporary.root, bundleId, specs, openAsset = { name ->
            opened += name; ByteArrayInputStream(assets.getValue(name))
        }, shouldStop = { false }))
        assertEquals(listOf("network.bin.lz4"), opened)
        assertBundle(directory)
    }

    @Test fun failedRepairPreservesTheExistingFileAndDoesNotPretendItIsVerified() {
        val directory = File(temporary.root, bundleId).apply { mkdirs() }
        val corrupt = File(directory, "network.bin.lz4").apply { writeText("previous corrupt bytes") }
        val original = corrupt.readBytes()
        val store = RapfiBundleStore()
        assertNull(store.prepare(temporary.root, bundleId, specs, openAsset = { name ->
            ByteArrayInputStream(if (name == "network.bin.lz4") "another bad asset".toByteArray() else assets.getValue(name))
        }, shouldStop = { false }))
        assertArrayEquals(original, corrupt.readBytes())
        assertNoTemporaryFiles(directory)
        assertBundle(requireNotNull(store.prepare(temporary.root, bundleId, specs,
            openAsset = { ByteArrayInputStream(assets.getValue(it)) }, shouldStop = { false })))
    }

    @Test fun cancellationDuringCopyClosesTheInputAndRemovesItsPartialFile() {
        var cancelled = false
        var closed = false
        var reads = 0
        val network = assets.getValue("network.bin.lz4")
        val onlyNetwork = listOf(RapfiBundledFile("network.bin.lz4", sha(network)))
        val store = RapfiBundleStore()
        assertNull(store.prepare(temporary.root, bundleId, onlyNetwork, openAsset = {
            object : ByteArrayInputStream(network) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (++reads == 1) cancelled = true
                    return count
                }
                override fun close() { closed = true; super.close() }
            }
        }, shouldStop = { cancelled }))
        assertEquals(1, reads)
        assertTrue(closed)
        assertTrue(temporary.root.walkTopDown().none { it.isFile })
        cancelled = false
        val directory = requireNotNull(store.prepare(temporary.root, bundleId, onlyNetwork,
            openAsset = { ByteArrayInputStream(network) }, shouldStop = { cancelled }))
        assertArrayEquals(network, File(directory, "network.bin.lz4").readBytes())
        assertNoTemporaryFiles(directory)
    }

    @Test fun rejectedHashesBundleNamesAndPathsNeverOpenAssetsOrCreateFiles() {
        val valid = specs.first()
        val invalidSpecs = listOf(emptyList(), listOf(valid, valid),
            listOf(valid.copy(name = "../config.toml")), listOf(valid.copy(name = "/config.toml")),
            listOf(valid.copy(name = "nested/config.toml")), listOf(valid.copy(name = "nested\\config.toml")),
            listOf(valid.copy(name = ".")), listOf(valid.copy(name = "..")), listOf(valid.copy(name = "")),
            listOf(valid.copy(sha256 = "")), listOf(valid.copy(sha256 = "z".repeat(64))))
        for (files in invalidSpecs) {
            assertNull(RapfiBundleStore().prepare(temporary.root, bundleId, files,
                openAsset = { error("invalid manifest must not open assets") }, shouldStop = { false }))
        }
        for (id in listOf("", "../" + bundleId, "g".repeat(64), "a".repeat(63))) {
            assertNull(RapfiBundleStore().prepare(temporary.root, id, specs,
                openAsset = { error("invalid bundle must not open assets") }, shouldStop = { false }))
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationBeforePreparationDoesNotOpenOrCreateAnything() {
        assertNull(RapfiBundleStore().prepare(temporary.root, bundleId, specs,
            openAsset = { error("cancelled request must not open assets") }, shouldStop = { true }))
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    private fun assertBundle(directory: File) {
        assertEquals(assets.keys, directory.listFiles()!!.map { it.name }.toSet())
        assets.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
        assertNoTemporaryFiles(directory)
    }
    private fun assertNoTemporaryFiles(directory: File) = assertTrue(directory.walkTopDown().none { it.name.startsWith("resource-") })
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
