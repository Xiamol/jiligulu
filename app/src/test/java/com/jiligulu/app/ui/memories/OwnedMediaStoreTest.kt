package com.jiligulu.app.ui.memories

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnedMediaStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun discardedCopiesAreRemovedButOriginalsNestedFilesAndUnknownExtensionsAreUntouched() = runBlocking {
        val root = temporary.newFolder("media")
        val store = OwnedMediaStore(root)
        val photo = image(root, "photos/draft.jpg")
        val poster = image(root, "posters/draft.png")
        val original = image(temporary.root, "gallery/original.jpg")
        val nested = image(root, "photos/nested/keep.jpg")
        val unknown = image(root, "photos/keep.png")
        store.release(listOf(photo.path, poster.path, original.path, nested.path, unknown.path)) { emptySet() }
        assertFalse(photo.exists()); assertFalse(poster.exists())
        assertTrue(original.exists()); assertTrue(nested.exists()); assertTrue(unknown.exists())
        assertNull(store.claim(original.path))
        assertNull(store.claim(File(root, "photos/../gallery/original.jpg").path))
    }

    @Test fun liveBillsTrashWishesAndAlreadyArchivedCardsProtectTheirFiles() = runBlocking {
        val root = temporary.newFolder("references")
        val store = OwnedMediaStore(root)
        val live = image(root, "photos/live.jpg")
        val trash = image(root, "photos/trash.jpg")
        val wish = image(root, "photos/wish.jpg")
        val card = image(root, "posters/card.png")
        val discarded = image(root, "photos/discarded.jpg")
        val referenced = setOf(live.canonicalPath, trash.canonicalPath, wish.canonicalPath, card.canonicalPath)
        store.release(listOf(live.path, trash.path, wish.path, card.path, discarded.path)) { referenced }
        for (file in listOf(live, trash, wish, card)) assertTrue(file.name, file.exists())
        assertFalse(discarded.exists())
    }

    @Test fun aFailedReferenceQueryKeepsAllCandidateFiles() = runBlocking {
        val root = temporary.newFolder("unavailable")
        val photo = image(root, "photos/draft.jpg")
        OwnedMediaStore(root).release(listOf(photo.path)) { throw IOException("Database unavailable") }
        assertTrue(photo.exists())
    }

    @Test fun overlappingCommitClaimsProtectTheFileAndCloseIdempotently() = runBlocking {
        val root = temporary.newFolder("commit")
        val photo = image(root, "posters/current.png")
        val store = OwnedMediaStore(root)
        val first = requireNotNull(store.claim(photo.path))
        val second = requireNotNull(store.claim(photo.path))
        store.release(listOf(photo.path)) { emptySet() }
        assertTrue(photo.exists())
        first.close(); first.close()
        store.release(listOf(photo.path)) { emptySet() }
        assertTrue(photo.exists())
        second.close()
        store.release(listOf(photo.path)) { setOf(photo.canonicalPath) }
        assertTrue(photo.exists())
        store.release(listOf(photo.path)) { emptySet() }
        assertFalse(photo.exists())
    }

    @Test fun aCommitThatStartsAndEndsDuringLookupInvalidatesTheStaleUnreferencedSnapshot() = runBlocking {
        val root = temporary.newFolder("racing-commit")
        val photo = image(root, "posters/saved.png")
        val store = OwnedMediaStore(root)
        var durableReferences = emptySet<String>()
        store.release(listOf(photo.path)) {
            val oldSnapshot = durableReferences
            requireNotNull(store.claim(photo.path)).use { durableReferences = setOf(photo.canonicalPath) }
            oldSnapshot // Simulate a DB lookup that began before the newly committed card was visible.
        }
        assertTrue("A committed card must never lose its image", photo.exists())
        store.release(listOf(photo.path)) { durableReferences }
        assertTrue(photo.exists())
    }

    @Test fun cancellationDuringLookupPreservesCandidates() = runBlocking {
        val root = temporary.newFolder("cancelled")
        val photo = image(root, "photos/new.jpg")
        try {
            OwnedMediaStore(root).release(listOf(photo.path)) { throw CancellationException("Lookup cancelled") }
            fail("Cancellation must remain cooperative")
        } catch (_: CancellationException) { }
        assertTrue(photo.exists())
    }

    private fun image(root: File, child: String): File = File(root, child).apply {
        parentFile!!.mkdirs(); writeText("An owned test image")
    }.canonicalFile
}
