package com.jiligulu.app.ui.littleworld

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PikafishModelStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun verifiedModelsAreReusedAcrossCallsAndAcrossNewStoreInstances() {
        val bytes = "test network bytes".toByteArray()
        val hash = sha(bytes)
        var copies = 0
        val store = PikafishModelStore()
        val first = requireNotNull(store.prepare(directory.root, hash, { copies++; ByteArrayInputStream(bytes) }) { false })
        assertArrayEquals(bytes, first.readBytes())
        assertEquals(first, store.prepare(directory.root, hash, { error("must reuse") }) { false })
        assertEquals(first, PikafishModelStore().prepare(directory.root, hash, { error("cold start hashes and reuses") }) { false })
        assertEquals(1, copies)
    }

    @Test fun mismatchedAndCancelledCopiesNeverBecomeTheModelFile() {
        val bytes = ByteArray(160_000) { (it % 251).toByte() }
        val store = PikafishModelStore()
        assertNull(store.prepare(directory.root, sha(bytes), { ByteArrayInputStream("wrong model".toByteArray()) }) { false })
        var polls = 0
        assertNull(store.prepare(directory.root, sha(bytes), { ByteArrayInputStream(bytes) }) { ++polls >= 6 })
        assertTrue(directory.root.walkTopDown().none { it.isFile })
    }

    @Test fun corruptedExistingModelIsReplacedOnlyWithAVerifiedAsset() {
        val bytes = "expected network".toByteArray()
        val hash = sha(bytes)
        val store = PikafishModelStore()
        val first = requireNotNull(store.prepare(directory.root, hash, { ByteArrayInputStream(bytes) }) { false })
        first.writeText("corrupt file")
        val restored = requireNotNull(store.prepare(directory.root, hash, { ByteArrayInputStream(bytes) }) { false })
        assertArrayEquals(bytes, restored.readBytes())
        assertTrue(first.parentFile!!.listFiles()!!.all { it.name == "pikafish.nnue" })
    }

    @Test fun absentDistributionHashDoesNotOpenAnyAsset() {
        assertNull(PikafishModelStore().prepare(directory.root, "", { error("no verified distribution") }) { false })
        assertTrue(directory.root.listFiles()!!.isEmpty())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
