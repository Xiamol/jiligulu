package com.jiligulu.app.data.repository

import android.app.Application
import androidx.room.Room
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ChatHistoryPagingTest {
    @Test fun readingOlderPagesRemainsCompleteWhenNewMessagesArriveAndIdsHaveGaps() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = ChatHistoryRepository(db)
            val expected = mutableListOf<Long>()
            repeat(175) { index ->
                expected += repo.insert(ChatMessageEntity(kind = "USER", content = "message $index"))
                if (index % 20 == 0) {
                    val action = repo.insert(ChatMessageEntity(kind = "ACTION"))
                    repo.deleteMessage(action)
                    // This is an internal marker, not an empty assistant bubble in the UI.
                    repo.insert(ChatMessageEntity(kind = "PENDING_DRAFT", status = "DISMISSED"))
                }
            }
            val first = repo.uiPageBefore(Long.MAX_VALUE, 81)
            assertEquals(81, first.size)
            val shown = first.takeLast(80)
            val newMessage = repo.insert(ChatMessageEntity(kind = "USER", content = "arrived after page 1"))
            val second = repo.uiPageBefore(shown.first().id, 81).takeLast(80)
            val third = repo.uiPageBefore(second.first().id, 81)
            assertEquals(15, third.size)
            val collected = third + second + shown
            assertEquals(expected, collected.map { it.id })
            assertEquals(collected.size, collected.distinctBy { it.id }.size)
            assertTrue(repo.uiPageBefore(collected.first().id, 81).isEmpty())
            assertEquals(newMessage, repo.uiPageBefore(Long.MAX_VALUE, 81).last().id)
            assertEquals(175, repo.getAll().count { it.kind == "USER" && it.id != newMessage })
        } finally { db.close() }
    }

    @Test fun pagingPreservesDraftPayloadsStatusesAndLatestPendingResponse() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = ChatHistoryRepository(db)
            val draft = ChatMessageEntity(kind = "DRAFT", draftPayload = "original payload", status = "EDITING")
            val draftId = repo.insert(draft)
            repeat(82) { repo.insert(ChatMessageEntity(kind = "ASSISTANT", content = "reply $it")) }
            val (_, pending) = repo.beginRequest("午饭 9", 1000)
            val initial = repo.uiPageBefore(Long.MAX_VALUE, 81).takeLast(80)
            assertEquals(pending, initial.last())
            assertFalse(initial.any { it.id == draftId })
            val older = repo.uiPageBefore(initial.first().id, 81)
            assertEquals(draft.copy(id = draftId), older.first())
            assertEquals("EDITING", repo.getById(draftId)!!.status)
            assertEquals(85, repo.getAll().size)
        } finally { db.close() }
    }
}
