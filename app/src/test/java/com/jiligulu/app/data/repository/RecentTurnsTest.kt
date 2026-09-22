package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.entity.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 回归：本轮的输入不得在模型请求里出现两次。
 *
 * `ChatViewModel.send()` 先落一条 USER、再落一条 ASSISTANT(PENDING)；随后组装的 history
 * 由 [AiRepository.chatTurnsFor] 产出。PENDING 的 assistant 会被滤掉，于是本轮那条 USER 就成了
 * 末尾「没有回复的 user」——它必须被剔掉，否则会和动态上下文里的 `用户这轮说：{input}` 重复，
 * 让模型以为用户连说了两轮同样的话。
 */
class RecentTurnsTest {
    private val now = 1_000_000_000L

    private fun user(id: Long, text: String) =
        ChatMessageEntity(id = id, kind = "USER", content = text, createdAt = now)

    private fun assistant(id: Long, text: String, status: String = "") =
        ChatMessageEntity(id = id, kind = "ASSISTANT", content = text, status = status, createdAt = now)

    private fun pending(id: Long, rawInput: String) =
        ChatMessageEntity(id = id, kind = "ASSISTANT", rawInput = rawInput, status = "PENDING", createdAt = now)

    @Test
    fun `the in-flight user message is not fed back as history`() {
        val messages = listOf(
            user(1, "午饭 20"), assistant(2, "记好了"),   // 上一轮（已完成）
            user(3, "晚饭 30"), pending(4, "晚饭 30")      // 本轮：USER 刚落、assistant 还在 PENDING
        )
        val turns = AiRepository.chatTurnsFor(messages, now)
        assertEquals(
            listOf("user" to "午饭 20", "assistant" to "记好了"),
            turns.map { it.role to it.content }
        )
        assertFalse("本轮输入不该出现在 history 里", turns.any { it.content == "晚饭 30" })
    }

    @Test
    fun `an earlier unanswered user message is still kept as history`() {
        // 上一轮的 assistant 被中断（INTERRUPTED）；本轮再发一条：只该裁掉本轮，保留更早那条。
        val messages = listOf(
            user(1, "午饭 20"), assistant(2, "被打断了", status = "INTERRUPTED"),
            user(3, "晚饭 30"), pending(4, "晚饭 30")
        )
        val turns = AiRepository.chatTurnsFor(messages, now)
        assertEquals(listOf("user" to "午饭 20"), turns.map { it.role to it.content })
    }

    @Test
    fun `the very first turn carries no history`() {
        val turns = AiRepository.chatTurnsFor(listOf(user(1, "5"), pending(2, "5")), now)
        assertTrue("第一条消息不该把本轮输入当成历史", turns.isEmpty())
    }
}
