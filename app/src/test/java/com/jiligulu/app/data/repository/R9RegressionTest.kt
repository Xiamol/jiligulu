package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.ChatTurn
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.domain.chat.ChatContextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T02a 第 2 轮回归（QA: 严过关）——**独立**复核 [AiRepository.chatTurnsFor] 的语义。
 *
 * 这组用例不是复读工程师的 [RecentTurnsTest]：它是从「规则本身该满足什么」出发重写的，
 * 刻意补上工程师没覆盖的反向场景（尤其是「末尾恰好是 assistant 时**不许**误裁」），
 * 并顺带把 MAX_MESSAGES=60 这个硬约束、以及 24h 窗口一起钉住。
 *
 * 被测契约（来自 [AiRepository.chatTurnsFor] 的 KDoc）：
 *  1. 只映射 USER / ASSISTANT 两种 kind 的原文；DRAFT / COMMAND / PENDING_DRAFT 一律为 null；
 *  2. 先按 24h 窗口过滤，再 `takeLast(MAX_MESSAGES)`；
 *  3. **仅当**结果末尾是「未配对的 user」时，裁掉末尾这**一条**（本轮输入）；
 *     末尾是 assistant、或结果为空时，一条都不裁。
 */
class R9HistoryRegressionTest {
    private val now = 1_700_000_000_000L
    private fun hour() = 3_600_000L

    private fun user(id: Long, text: String, at: Long = now) =
        ChatMessageEntity(id = id, kind = "USER", content = text, createdAt = at)

    private fun assistant(id: Long, text: String, status: String = "", at: Long = now) =
        ChatMessageEntity(id = id, kind = "ASSISTANT", content = text, status = status, createdAt = at)

    /** 本轮刚落库、还没有回复的 assistant 占位（PENDING）。 */
    private fun pending(id: Long, at: Long = now) =
        ChatMessageEntity(id = id, kind = "ASSISTANT", rawInput = "x", status = "PENDING", createdAt = at)

    private fun pairs(turns: List<ChatTurn>) = turns.map { it.role to it.content }
    private fun roles(turns: List<ChatTurn>) = turns.map { it.role }

    private fun draft(id: Long, status: String = "CONFIRMED") =
        ChatMessageEntity(id = id, kind = "DRAFT", status = status, draftPayload = "{}", createdAt = now)

    private fun command(id: Long) =
        ChatMessageEntity(id = id, kind = "COMMAND", status = "EDITING", draftPayload = "{}", createdAt = now)

    private fun pendingDraft(id: Long) =
        ChatMessageEntity(id = id, kind = "PENDING_DRAFT", draftPayload = "{}", createdAt = now)

    private fun turns(vararg messages: ChatMessageEntity) =
        AiRepository.chatTurnsFor(messages.toList(), now)

    // ---------- 场景 1：正常一轮完成，保留它；本轮输入不进历史 ----------
    @Test
    fun `a completed turn is kept and the in-flight user is dropped`() {
        val t = turns(
            user(1, "午饭 20"), assistant(2, "记好了"),
            user(3, "晚饭 30"), pending(4)
        )
        assertEquals(listOf("user" to "午饭 20", "assistant" to "记好了"), pairs(t))
        assertFalse("本轮「晚饭 30」不得出现在历史里", t.any { it.content == "晚饭 30" })
    }

    // ---------- 场景 2：上一轮 assistant 被中断，本轮再发 → 保留更早 user、裁掉本轮 ----------
    @Test
    fun `an earlier unanswered user survives when its assistant was interrupted`() {
        val interrupted = turns(
            user(1, "午饭 20"), assistant(2, "被打断了", status = "INTERRUPTED"),
            user(3, "晚饭 30"), pending(4)
        )
        assertEquals("只该裁本轮的 user，更早那条要留", listOf("user" to "午饭 20"), pairs(interrupted))

        // 上一轮 assistant 是空串（既没成 reply 也没标 INTERRUPTED）——同样视为「未回复」，保留更早 user。
        val blankReply = turns(
            user(1, "午饭 20"), assistant(2, ""),
            user(3, "晚饭 30"), pending(4)
        )
        assertEquals(listOf("user" to "午饭 20"), pairs(blankReply))
    }

    // ---------- 场景 3：连续两条未配对的 user → 只裁最后一条 ----------
    @Test
    fun `two consecutive unpaired users drop only the last one`() {
        assertEquals(listOf("user" to "a"), pairs(turns(user(1, "a"), user(2, "b"))))
        assertEquals(listOf("user" to "a", "user" to "b"),
            pairs(turns(user(1, "a"), user(2, "b"), user(3, "c"))))
    }

    // ---------- 场景 4：第一条消息 / 空历史 → 历史为空 ----------
    @Test
    fun `the first message yields no history`() {
        assertTrue("空历史必须是空", turns().isEmpty())
        assertTrue("第一条消息不该把本轮输入当历史", turns(user(1, "5"), pending(2)).isEmpty())
    }

    // ---------- 场景 5：混入 DRAFT / COMMAND / PENDING_DRAFT → 全为 null ----------
    @Test
    fun `only USER and ASSISTANT are mapped, card kinds are null`() {
        val t = turns(
            user(1, "u1"), draft(2), command(3), pendingDraft(4),
            assistant(5, "a2"), user(6, "本轮"), pending(7)
        )
        assertEquals("只应有 user/assistant 两种 role", listOf("user", "assistant"), roles(t))
        assertEquals(setOf("u1", "a2"), t.map { it.content }.toSet())
        assertTrue("末尾本轮 user 必须被裁掉", t.none { it.content == "本轮" })
        assertFalse("草稿/指令卡不得泄漏进历史", t.any { it.content == "{}" })
    }

    // ---------- 重点复核：末尾恰好是 assistant 时，一条都不许裁 ----------
    @Test
    fun `a trailing assistant is never dropped`() {
        assertEquals(
            listOf("user" to "u1", "assistant" to "a1"),
            pairs(turns(user(1, "u1"), assistant(2, "a1")))
        )
        assertEquals(
            listOf("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2"),
            pairs(turns(user(1, "u1"), assistant(2, "a1"), user(3, "u2"), assistant(4, "a2")))
        )
    }

    // ---------- 硬约束：MAX_MESSAGES=60 未被改动，且 takeLast(60) 真正生效 ----------
    @Test
    fun `the 60-message window is untouched and enforced before the drop`() {
        assertEquals("硬约束：历史窗口仍是 60 条", 60, ChatContextBuilder.MAX_MESSAGES)
        // 60 组完整配对 = 120 条消息 → takeLast(60) 应恰好留下 60 条，且末尾是 assistant（不裁）。
        val oneHundredTwenty = buildList {
            repeat(60) { i ->
                add(user(i * 2L + 1, "u$i"))
                add(assistant(i * 2L + 2, "a$i"))
            }
        }
        val t = turns(*oneHundredTwenty.toTypedArray())
        assertEquals("窗口上限应为 60", 60, t.size)
        assertEquals("末尾是 assistant，不该被裁", "assistant", t.last().role)
    }

    // ---------- 24h 窗口：过老的对话不进历史 ----------
    @Test
    fun `turns older than the 24h window are excluded`() {
        val t = turns(
            user(1, "昨天的话", at = now - 25 * hour()),
            assistant(2, "昨天回的", at = now - 25 * hour()),
            user(3, "今天的话", at = now - 1 * hour()),
            assistant(4, "今天回的", at = now - 1 * hour())
        )
        assertEquals(listOf("user" to "今天的话", "assistant" to "今天回的"), pairs(t))
    }

    // ---------- 空白内容的 user/assistant 被丢弃，不影响末尾判定 ----------
    @Test
    fun `blank user content is filtered before the trailing check`() {
        val t = turns(
            user(1, "   "), assistant(2, "hi"),
            user(3, "本轮"), pending(4)
        )
        assertEquals(listOf("assistant" to "hi"), pairs(t))
    }
}

/**
 * P1 回归（QA: 严过关）——提示词资源在**运行时**（打包后的 assets）也必须逐字节可复现。
 *
 * 与 R9SystemSegmentTest 的「无 CRLF」互补：这里直接钉住**字节数**，
 * 因为字节数是最抗篡改的不变量——多一个 `\r` 就会立刻让断言失败。
 *
 * ⚠️ 维护约定：**只要改了提示词正文，就要同步更新下面的字节数**（用 LF 版文件量）；
 * 若数字对不上但内容确实改了，先量当前 LF 字节数再更新，别直接删断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9PromptAssetByteRegressionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun bytesOf(path: String) = ctx.assets.open(path).use { it.readBytes() }

    @Test
    fun `packaged prompt assets keep the exact LF byte sizes`() {
        val system = bytesOf("prompts/parse_bill_system.txt")
        val context = bytesOf("prompts/parse_bill_context.txt")

        // 8090 = v0.6 T04 补「做不到的事」边界后的 LF 字节数
        // （禁止幻觉声称「已清空回收站 / 已改好设置」——用户实际遇到的假承诺）。
        assertEquals("system 资源字节数必须与仓库 LF 版一致（CRLF 检出会让每个 \\r 多占一字节）", 8090, system.size)
        assertEquals("context 资源字节数必须与仓库 LF 版一致（CRLF 版会多出 \\r 变成 324）", 314, context.size)

        assertEquals("system 不得含 CR", 0, system.count { it == '\r'.code.toByte() })
        assertEquals("context 不得含 CR", 0, context.count { it == '\r'.code.toByte() })
    }
}
