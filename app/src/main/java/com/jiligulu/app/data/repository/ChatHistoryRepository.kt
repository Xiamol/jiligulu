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

    // ---------- 草稿生命周期（v0.6 R3） ----------

    /** 删除一张草稿（打 `DELETED` 标记），返回受影响行数。 */
    suspend fun deleteDraft(id: Long): Int = dao.markDraftDeleted(id)

    /** 清空草稿页，返回清掉的张数。 */
    suspend fun deleteAllActiveDrafts(): Int = dao.markActiveDraftsDeleted()

    /**
     * 物理删除一条消息——只给**一次性卡片**用（跳转卡用掉即销毁）。
     *
     * 对话正文不走这里：历史只追加、不回改（R9 铁律）。卡片也不进 AI 上下文，
     * 所以删它不会动到任何 prompt 前缀。
     */
    suspend fun deleteMessage(id: Long): Int = dao.deleteById(id)

    /** 活跃草稿流（EDITING / DISMISSED，最新在前），供回收站草稿页展示。 */
    fun observeActiveDrafts(): Flow<List<ChatMessageEntity>> = dao.observeActiveDrafts()

    /**
     * 写一张指令卡的载荷。
     *
     * 必须挑对具体 DAO 方法：`updateDraft` 的 WHERE 里带 `kind = 'DRAFT'`，
     * 拿它去存指令卡的勾选状态会静默地更新 0 行。
     */
    suspend fun updateCard(id: Long, kind: String, payload: String): Int =
        if (kind.equals("COMMAND", true)) dao.updateCommand(id, payload) else dao.updateDraft(id, payload)

    /**
     * 把一串写入包进一个事务。
     *
     * 改账/删账要「新建分类 + 改每一条账单 + 写卡片状态」三者一致，
     * 但那条链路跨了三个 Repository——各家自己开事务会各自提交，
     * 中途出错就会留下改了一半的账本。所以由这里统一开口子。
     */
    suspend fun <T> withTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    /** A sent message always has a durable pending response, including when the screen closes. */
    suspend fun beginRequest(input: String, requestedAt: Long): Pair<ChatMessageEntity, ChatMessageEntity> =
        database.withTransaction {
            val user = ChatMessageEntity(kind = "USER", content = input, createdAt = requestedAt)
            val pending = ChatMessageEntity(kind = "ASSISTANT", rawInput = input,
                status = "PENDING", createdAt = requestedAt)
            user.copy(id = dao.insert(user)) to pending.copy(id = dao.insert(pending))
        }

    // ---------- 待补充账（用户只说了金额） ----------

    /**
     * 挂起一条「待补充」的账。
     *
     * 复用 DRAFT 行的 `draftPayload`，不新开表：这就是一条临时草稿，
     * 生命周期只到下一条消息为止，为它做一次 schema 迁移不值得。
     * 同一时刻只留一条挂起记录——否则用户连发两个数字，「补齐」该补到哪条就说不清了。
     */
    suspend fun suspendPending(payload: String, requestedAt: Long): Long = database.withTransaction {
        dao.clearPendingPayload()
        dao.insert(
            ChatMessageEntity(
                kind = "PENDING_DRAFT",
                draftPayload = payload,
                // 必须显式写 EDITING：DAO 是按这个状态找挂起记录的，
                // 留空的话插入能成功，但再查就查不到了（表现为「挂起账凭空消失」）。
                status = "EDITING",
                createdAt = requestedAt
            )
        )
    }

    /** 最近的挂起记录；没有就返回 null。 */
    suspend fun latestPending(): ChatMessageEntity? = dao.latestPending()

    suspend fun clearPending(): Int = dao.clearPendingPayload()

    /** 把挂起记录标记成已补齐，避免它在下一次 [latestPending] 里再冒出来。 */
    suspend fun consumePending(id: Long): Int = dao.consumePending(id)

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
