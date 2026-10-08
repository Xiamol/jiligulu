package com.jiligulu.app.data.repository

import android.app.Application
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConversationPhotoCleanupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "conversation-photo-cleanup.db"
    private val db = AppDatabase.build(context, name)
    private val history = ChatHistoryRepository(db)
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun payload(path: String) = DraftHistoryCodec.encode(listOf(DraftUi("9", categoryName = "吃饭", photoUri = path)))

    @Test fun resetReportsOnlyRemovedDraftPhotosAfterCommitAndKeepsBillAndActiveDraftReferences() = runBlocking {
        val shared = "/private/life-memories/photos/shared.jpg"
        val deleted = "/private/life-memories/photos/deleted.jpg"
        val retained = "/private/life-memories/photos/editing.jpg"
        val bill = BillRepository(db.billDao()).addFromAi(900, BillType.EXPENSE, 1, "午饭", "", "午饭9元", photoUri = shared)
        val active = history.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING", draftPayload = payload(retained)))
        history.insert(ChatMessageEntity(kind = "DRAFT", status = "CONFIRMED", draftPayload = payload(shared)))
        history.insert(ChatMessageEntity(kind = "DRAFT", status = "DELETED", draftPayload = payload(deleted)))
        var cleanup = emptyList<String>()
        assertTrue(history.clearConversation { paths ->
            assertEquals(1L, history.conversationGeneration.value)
            cleanup = paths
        })
        assertEquals(setOf(shared, deleted), cleanup.toSet())
        assertEquals(listOf(active), history.getAll().map { it.id })
        assertEquals(shared, db.billDao().getById(bill)!!.photoUri)
        assertEquals(listOf(retained), com.jiligulu.app.ui.memories.chatMediaReferences(history.mediaReferenceRows()))
    }

    @Test fun pendingResponseRefusesResetAndNeverAuthorizesCleanup() = runBlocking {
        history.insert(ChatMessageEntity(kind = "DRAFT", status = "CONFIRMED", draftPayload = payload("owned.jpg")))
        history.beginRequest("午饭9元", 100)
        var invoked = false
        assertFalse(history.clearConversation { invoked = true })
        assertFalse(invoked)
        assertEquals(0L, history.conversationGeneration.value)
    }

    @Test fun malformedRemovedMetadataDoesNotGuessPathsOrBlockTheExplicitHistoryReset() = runBlocking {
        history.insert(ChatMessageEntity(kind = "DRAFT", status = "CONFIRMED", draftPayload = "broken"))
        var invoked = false
        assertTrue(history.clearConversation { invoked = true })
        assertFalse(invoked)
        assertTrue(history.getAll().isEmpty())
    }
}
