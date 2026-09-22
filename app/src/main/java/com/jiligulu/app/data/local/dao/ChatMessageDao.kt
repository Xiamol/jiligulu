package com.jiligulu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessageEntity?

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET draftPayload = :payload WHERE id = :id AND kind = 'DRAFT' AND status = 'EDITING'")
    suspend fun updateDraft(id: Long, payload: String): Int

    /**
     * 作废一张还没提交的卡片（草稿卡或改账/删账指令卡）。
     *
     * 必须同时覆盖 COMMAND：指令卡被取消后如果状态还停在 EDITING，
     * [com.jiligulu.app.data.repository.AiRepository.commitCommands] 就会照常执行——
     * 用户明明点了「取消」，账却被改了。
     */
    @Query("UPDATE chat_messages SET status = 'DISMISSED' WHERE id = :id AND kind IN ('DRAFT', 'COMMAND') AND status = 'EDITING'")
    suspend fun dismissDraft(id: Long): Int

    /** 指令卡的勾选状态也要能存下来。 */
    @Query("UPDATE chat_messages SET draftPayload = :payload WHERE id = :id AND kind = 'COMMAND' AND status = 'EDITING'")
    suspend fun updateCommand(id: Long, payload: String): Int

    @Query("UPDATE chat_messages SET status = 'CONFIRMED', savedCount = :count WHERE id = :id AND kind = 'DRAFT' AND status = 'EDITING'")
    suspend fun markConfirmed(id: Long, count: Int): Int

    // ---------- 草稿生命周期（v0.6 R3） ----------

    /**
     * 删除一张草稿（打 `DELETED` 标记，不物理删，保留可追溯的历史行）。
     *
     * WHERE 必须同时覆盖 `EDITING` 与 `DISMISSED`：新流程不再产生 `DISMISSED`，
     * 但历史遗留记录仍可能停在这个状态，只判 `EDITING` 会让它们无法删除。
     */
    @Query("UPDATE chat_messages SET status = 'DELETED' WHERE id = :id AND kind = 'DRAFT' AND status IN ('EDITING', 'DISMISSED')")
    suspend fun markDraftDeleted(id: Long): Int

    /** 清空草稿页：把当前所有活跃草稿一并打上 `DELETED`，返回清掉的张数。 */
    @Query("UPDATE chat_messages SET status = 'DELETED' WHERE kind = 'DRAFT' AND status IN ('EDITING', 'DISMISSED')")
    suspend fun markActiveDraftsDeleted(): Int

    /** 草稿页数据源：活跃草稿（EDITING / DISMISSED），最新在前的。 */
    @Query("SELECT * FROM chat_messages WHERE kind = 'DRAFT' AND status IN ('EDITING', 'DISMISSED') ORDER BY id DESC")
    fun observeActiveDrafts(): Flow<List<ChatMessageEntity>>

    @Query("UPDATE chat_messages SET status = 'INTERRUPTED', content = :message WHERE kind = 'ASSISTANT' AND status = 'PENDING'")
    suspend fun markPendingInterrupted(message: String): Int

    // ---------- 待补充账（用户只说了金额，等下一句补名目） ----------

    /** 挂起记录按时间倒序取一条。同一时刻只应该有一条。 */
    @Query("SELECT * FROM chat_messages WHERE kind = 'PENDING_DRAFT' AND status = 'EDITING' ORDER BY id DESC LIMIT 1")
    suspend fun latestPending(): ChatMessageEntity?

    /** 清掉所有还挂着的待补充账。 */
    @Query("UPDATE chat_messages SET status = 'DISMISSED' WHERE kind = 'PENDING_DRAFT' AND status = 'EDITING'")
    suspend fun clearPendingPayload(): Int

    /** 把某一条挂起记录标记成已补充。 */
    @Query("UPDATE chat_messages SET status = 'DISMISSED' WHERE id = :id AND kind = 'PENDING_DRAFT' AND status = 'EDITING'")
    suspend fun consumePending(id: Long): Int
}
