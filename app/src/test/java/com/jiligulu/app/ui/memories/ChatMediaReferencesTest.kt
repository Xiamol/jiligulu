package com.jiligulu.app.ui.memories

import com.jiligulu.app.data.local.dao.ChatMediaReferenceRow
import com.jiligulu.app.ui.chat.ChatPhotoPayload
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaReferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun photo(root: File, name: String) = File(root, "photos/$name.jpg").apply { parentFile!!.mkdirs(); writeText("fixture") }
    @Test fun durableUserAttachmentsAndUnDeletedDraftsProtectCopiesDuringDialogCleanup() = runBlocking {
        val root = temporary.newFolder("media")
        val user = photo(root, "user")
        val draft = photo(root, "draft")
        val deleted = photo(root, "deleted")
        val discarded = photo(root, "discarded")
        val rows = listOf(
            ChatMediaReferenceRow("USER", "", ChatPhotoPayload.encode(user.path)),
            ChatMediaReferenceRow("DRAFT", "DISMISSED", DraftHistoryCodec.encode(listOf(DraftUi(photoUri = draft.path)))),
            ChatMediaReferenceRow("DRAFT", "DELETED", DraftHistoryCodec.encode(listOf(DraftUi(photoUri = deleted.path)))))
        OwnedMediaStore(root).release(listOf(user.path, draft.path, deleted.path, discarded.path)) {
            chatMediaReferences(rows).map { File(it).canonicalPath }.toSet()
        }
        assertTrue(user.exists()); assertTrue(draft.exists())
        assertFalse(deleted.exists()); assertFalse(discarded.exists())
    }

    @Test fun malformedLiveAttachmentMetadataKeepsCandidatesRatherThanGuessingTheyAreUnreferenced() = runBlocking {
        val root = temporary.newFolder("malformed")
        val user = photo(root, "user")
        val store = OwnedMediaStore(root)
        for (kind in listOf("USER", "DRAFT")) {
            store.release(listOf(user.path)) {
                chatMediaReferences(listOf(ChatMediaReferenceRow(kind, "EDITING", "{broken"))).toSet()
            }
            assertTrue(user.exists())
        }
    }
}
