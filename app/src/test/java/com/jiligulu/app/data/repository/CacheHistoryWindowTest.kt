package com.jiligulu.app.data.repository

import android.app.Application
import androidx.room.Room
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.domain.chat.ChatContextBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CacheHistoryWindowTest {
    @Test fun prefixStaysAnchoredUntilBatchEvictionAndStoredHistoryIsIntact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = ChatHistoryRepository(db)
            repeat(60) { i -> repo.insert(ChatMessageEntity(kind = if (i % 2 == 0) "USER" else "ASSISTANT", content = "message$i", createdAt = 1000)) }
            val initial = repo.recentForAi(500)
            repeat(78) { i -> repo.insert(ChatMessageEntity(kind = if (i % 2 == 0) "USER" else "ASSISTANT", content = "next$i", createdAt = 1001)) }
            val growing = repo.recentForAi(500)
            assertEquals(initial, growing.take(60))
            assertEquals(138, growing.size)
            repo.insert(ChatMessageEntity(kind = "DRAFT", content = "not history", createdAt = 1002))
            repo.insert(ChatMessageEntity(kind = "ASSISTANT", status = "PENDING", content = "not history", createdAt = 1002))
            assertEquals(growing, repo.recentForAi(500))
            repo.insert(ChatMessageEntity(kind = "USER", content = "last user", createdAt = 1003))
            val current = repo.recentForAi(500)
            assertEquals(138, AiRepository.chatTurnsFor(current, 1004, current.size).size)
            repo.insert(ChatMessageEntity(kind = "ASSISTANT", content = "last answer", createdAt = 1003))
            val trimmed = repo.recentForAi(500)
            assertEquals(60, trimmed.size)
            assertEquals(growing[80], trimmed.first())
            assertEquals(142, repo.getAll().size)
            assertTrue(repo.recentForAi(1005).isEmpty())
        } finally { db.close() }
    }
    @Test fun windowNeverShrinksBelowOriginalRetentionOrGrowsWithoutBound() {
        for (count in 60..10000) assertTrue(ChatContextBuilder.historyWindowSize(count) in 60..139)
    }
}
