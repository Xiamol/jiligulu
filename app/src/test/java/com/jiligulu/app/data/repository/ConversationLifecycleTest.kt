package com.jiligulu.app.data.repository

import android.app.Application
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConversationLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "conversation-lifecycle.db"
    private val db = AppDatabase.build(context, name)
    private val history = ChatHistoryRepository(db)
    private val bills = BillRepository(db.billDao())

    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun clearingHistoryKeepsLedgerTrashAndBothKindsOfActiveDraft() = runBlocking {
        withTimeout(10_000) {
            val live = bills.addManual(1200, BillType.EXPENSE, 1, "午饭", "")
            val trash = bills.addManual(800, BillType.EXPENSE, 1, "旧账", "")
            bills.moveToTrash(trash)
            val editing = history.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING", draftPayload = "a"))
            val collapsed = history.insert(ChatMessageEntity(kind = "DRAFT", status = "DISMISSED", draftPayload = "b"))
            listOf("USER", "ASSISTANT", "COMMAND", "APP_ACTION", "ACTION", "PENDING_DRAFT").forEach {
                history.insert(ChatMessageEntity(kind = it, status = "EDITING", content = "history"))
            }
            history.insert(ChatMessageEntity(kind = "DRAFT", status = "CONFIRMED"))
            history.insert(ChatMessageEntity(kind = "DRAFT", status = "DELETED"))
            assertTrue(history.clearConversation())
            assertEquals(listOf(editing, collapsed), history.getAll().map { it.id })
            assertEquals(listOf(live), db.billDao().observeAll().first().map { it.id })
            assertEquals(listOf(trash), bills.trash().map { it.id })
            assertEquals(3, db.categoryDao().count())
            assertEquals(1L, history.conversationGeneration.value)
        }
    }

    @Test fun clearIsRefusedWhileAssistantResponseIsPending() = runBlocking {
        history.beginRequest("早餐9元", 100)
        val before = history.getAll()
        assertFalse(history.clearConversation())
        assertEquals(before, history.getAll())
        assertEquals(0L, history.conversationGeneration.value)
        history.markPendingInterrupted()
        assertTrue(history.clearConversation())
    }

    @Test fun legacyCollapsedDraftCanEditAndConfirmOnlyOnce() = runBlocking {
        withTimeout(10_000) {
            val id = history.insert(ChatMessageEntity(kind = "DRAFT", status = "DISMISSED", draftPayload = "old"))
            assertEquals(1, history.updateDraft(id, "edited"))
            assertEquals("edited", history.getById(id)!!.draftPayload)
            val inserts = AtomicInteger()
            (1..4).map { async(Dispatchers.IO) {
                history.confirmDraftAtomically(id, "final") {
                    inserts.incrementAndGet()
                    bills.addManual(900, BillType.EXPENSE, 1, "早餐", "")
                    1
                }
            } }.awaitAll()
            assertEquals(1, inserts.get())
            assertEquals(1, db.billDao().observeAll().first().size)
            assertEquals("CONFIRMED", history.getById(id)!!.status)
        }
    }

    @Test fun deletedDraftCannotBeEditedConfirmedOrPhysicallyRemovedByActionApi() = runBlocking {
        val id = history.insert(ChatMessageEntity(kind = "DRAFT", status = "DELETED"))
        assertEquals(0, history.updateDraft(id, "new"))
        assertEquals(0, history.deleteMessage(id))
        var invoked = false
        try {
            history.confirmDraftAtomically(id) { invoked = true; 1 }
            fail("Deleted draft must not be confirmable")
        } catch (_: IllegalStateException) { }
        assertFalse(invoked)
        assertEquals("DELETED", history.getById(id)!!.status)
        val action = history.insert(ChatMessageEntity(kind = "ACTION"))
        assertEquals(1, history.deleteMessage(action))
    }

    @Test fun staleDetailEditorCannotModifyTrashedBill() = runBlocking {
        val id = bills.addManual(900, BillType.EXPENSE, 1, "早餐", "", 100)
        bills.moveToTrash(id)
        assertEquals(0, db.billDao().updateDetails(id, 2500, "晚餐", 200))
        assertEquals("早餐", bills.getById(id)!!.detail)
        assertEquals(900L, bills.getById(id)!!.amountFen)
    }
}
