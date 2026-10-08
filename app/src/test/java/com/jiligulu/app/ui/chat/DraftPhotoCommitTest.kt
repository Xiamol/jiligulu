package com.jiligulu.app.ui.chat

import com.jiligulu.app.ui.memories.OwnedMediaStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftPhotoCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun copy(name: String): File = File(File(temporary.root, "photos").apply { mkdirs() }, "$name.jpg")
        .apply { writeText("owned test copy") }

    @Test fun durableReplacementRechecksNewReferencesAndOnlyThenReleasesTheOldCopy() = runBlocking {
        val store = OwnedMediaStore(temporary.root)
        val old = copy("old"); val next = copy("new")
        var references = setOf(old.canonicalPath)
        val order = ArrayList<String>()
        val applied = commitPickedDraftPhoto(old.path, { next.path }, claim = { path ->
            order += "claim"
            val held = store.claim(path)!!
            AutoCloseable { order += "close"; held.close() }
        }, save = { path ->
            assertTrue(old.isFile); assertTrue(next.isFile)
            assertEquals(next.path, path)
            references = setOf(next.canonicalPath); order += "save"; true
        }, release = { paths ->
            order += "release:${File(paths.single()).name}"
            store.release(paths) { references }
        })
        assertTrue(applied)
        assertTrue(next.isFile)
        assertFalse(old.exists())
        assertEquals(listOf("claim", "save", "close", "release:new.jpg", "release:old.jpg"), order)
    }

    @Test fun aRejectedOrFailedSaveRemovesOnlyThisImportAndKeepsThePriorPhoto() = runBlocking {
        for (throwing in listOf(false, true)) {
            val store = OwnedMediaStore(temporary.root)
            val old = copy("old-$throwing"); val next = copy("new-$throwing")
            val released = ArrayList<String>()
            val result = runCatching { commitPickedDraftPhoto(old.path, { next.path }, store::claim,
                save = { if (throwing) error("durable write failed") else false }, release = { paths ->
                    released += paths
                    store.release(paths) { setOf(old.canonicalPath) }
                }) }
            if (throwing) assertTrue(result.isFailure) else assertFalse(result.getOrThrow())
            assertTrue(old.isFile)
            assertFalse(next.exists())
            assertEquals(listOf(next.path), released)
        }
    }

    @Test fun cancellationBeforeWritingClosesItsClaimAndCleansOnlyItsNewCopy() = runBlocking {
        val store = OwnedMediaStore(temporary.root)
        val old = copy("old"); val next = copy("new")
        var writes = 0
        val task = async {
            commitPickedDraftPhoto(old.path, copy = {
                currentCoroutineContext().cancel()
                next.path
            }, claim = store::claim, save = { writes++; true }, release = { paths ->
                store.release(paths) { setOf(old.canonicalPath) }
            })
        }
        assertTrue(runCatching { task.await() }.isFailure)
        task.join()
        assertEquals(0, writes)
        assertTrue(old.isFile)
        assertFalse(next.exists())
    }

    @Test fun disappearingUiCannotDeleteACommittedPhotoOrLeaveTheOldCopyBehind() = runBlocking {
        val store = OwnedMediaStore(temporary.root)
        val old = copy("old"); val next = copy("new")
        var references = setOf(old.canonicalPath)
        val entered = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val task = async {
            commitPickedDraftPhoto(old.path, { next.path }, store::claim, save = {
                entered.complete(Unit)
                finishWrite.await()
                references = setOf(next.canonicalPath)
                true
            }, release = { paths -> store.release(paths) { references } })
        }
        entered.await()
        // A UI disposal cleanup races with the suspended database commit.
        store.release(listOf(next.path)) { emptySet() }
        assertTrue(next.isFile)
        task.cancel()
        finishWrite.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(next.isFile)
        assertFalse(old.exists())
    }

    @Test fun removalWaitsForItsDurableFieldChangeAndARejectedRemovalRetainsTheFile() = runBlocking {
        val store = OwnedMediaStore(temporary.root)
        val old = copy("old")
        assertFalse(removeDraftPhoto(old.path, save = { assertNull(it); false },
            release = { error("a rejected removal must not release anything") }))
        assertTrue(old.isFile)
        var committed = false
        assertTrue(removeDraftPhoto(old.path, save = { assertNull(it); committed = true; true }, release = { paths ->
            assertTrue(committed)
            store.release(paths) { emptySet() }
        }))
        assertFalse(old.exists())
    }
}
