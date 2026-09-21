package com.jiligulu.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One durable conversation. Draft payloads keep the editable fields until confirmation. */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** USER, ASSISTANT or DRAFT. */
    val kind: String,
    val content: String = "",
    val rawInput: String = "",
    val draftPayload: String = "",
    /** Draft: EDITING / CONFIRMED / DISMISSED. Reply: PENDING / INTERRUPTED or empty. */
    val status: String = "",
    val savedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
