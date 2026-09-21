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

    @Query("UPDATE chat_messages SET status = 'DISMISSED' WHERE id = :id AND kind = 'DRAFT' AND status = 'EDITING'")
    suspend fun dismissDraft(id: Long): Int

    @Query("UPDATE chat_messages SET status = 'CONFIRMED', savedCount = :count WHERE id = :id AND kind = 'DRAFT' AND status = 'EDITING'")
    suspend fun markConfirmed(id: Long, count: Int): Int

    @Query("UPDATE chat_messages SET status = 'INTERRUPTED', content = :message WHERE kind = 'ASSISTANT' AND status = 'PENDING'")
    suspend fun markPendingInterrupted(message: String): Int
}
