package com.jiligulu.app.data.repository

import androidx.room.withTransaction
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

class ChatHistoryRepository(private val database: AppDatabase) {
    private val dao = database.chatMessageDao()

    fun observeAll(): Flow<List<ChatMessageEntity>> = dao.observeAll()
    suspend fun getAll(): List<ChatMessageEntity> = dao.getAll()
    suspend fun getById(id: Long): ChatMessageEntity? = dao.getById(id)
    suspend fun insert(message: ChatMessageEntity): Long = dao.insert(message)
    suspend fun update(message: ChatMessageEntity) = dao.update(message)
    suspend fun updateDraft(id: Long, payload: String): Int = dao.updateDraft(id, payload)
    suspend fun dismissDraft(id: Long): Int = dao.dismissDraft(id)

    /** A sent message always has a durable pending response, including when the screen closes. */
    suspend fun beginRequest(input: String, requestedAt: Long): Pair<ChatMessageEntity, ChatMessageEntity> =
        database.withTransaction {
            val user = ChatMessageEntity(kind = "USER", content = input, createdAt = requestedAt)
            val pending = ChatMessageEntity(kind = "ASSISTANT", rawInput = input,
                status = "PENDING", createdAt = requestedAt)
            user.copy(id = dao.insert(user)) to pending.copy(id = dao.insert(pending))
        }

    suspend fun markPendingInterrupted(): Int = dao.markPendingInterrupted(
        "上次对话中断了，这条消息还没有生成账单。可以重新发送，我会再帮你看看。"
    )

    /**
     * Category creation, every bill, and confirmation status share one Room transaction.
     * Re-entering an already confirmed draft never invokes [insertBills] again. An exception,
     * including cancellation, rolls back all writes and leaves the draft editable.
     * The callback must only use repositories backed by this same database.
     */
    suspend fun confirmDraftAtomically(
        messageId: Long,
        finalPayload: String? = null,
        insertBills: suspend () -> Int
    ): Int =
        database.withTransaction {
            val draft = checkNotNull(dao.getById(messageId)) { "账单草稿不存在" }
            check(draft.kind == "DRAFT") { "这条消息不是账单草稿" }
            if (draft.status == "CONFIRMED") return@withTransaction draft.savedCount
            check(draft.status == "EDITING") { "这条账单草稿已经取消" }
            if (finalPayload != null) check(dao.updateDraft(messageId, finalPayload) == 1)
            val count = insertBills()
            check(count > 0) { "请至少选择一笔有效账单" }
            check(dao.markConfirmed(messageId, count) == 1) { "账单草稿状态已变化" }
            count
        }
}
