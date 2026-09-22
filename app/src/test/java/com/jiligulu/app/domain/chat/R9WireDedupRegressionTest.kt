package com.jiligulu.app.domain.chat

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.ChatTurn
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * P2 端到端回归（QA: 严过关）——**整条请求体**里本轮输入只能出现一次。
 *
 * 前面的 [com.jiligulu.app.data.repository.R9HistoryRegressionTest] 只验了 chatTurnsFor 这个纯函数；
 * 这里把真实链路接起来：真模板渲染动态段 + chatTurnsFor 出历史 + [DeepSeekClient] 组装 messages，
 * 截获发往 DeepSeek 的**完整 JSON**，断言本轮原话在「各 message 的 content」里恰好出现一次。
 *
 * 这正是用户报的「输入出现两次」缺陷的现场复现：若 history 不裁末尾 user，
 * 这句话会同时出现在「历史 user 轮」和「用户这轮说：」两处 → 计数为 2 → 本用例失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9WireDedupRegressionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L
    private val input = "晚饭 30"

    private fun asset(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }

    private fun user(id: Long, text: String) =
        ChatMessageEntity(id = id, kind = "USER", content = text, createdAt = now)

    private fun assistant(id: Long, text: String, status: String = "") =
        ChatMessageEntity(id = id, kind = "ASSISTANT", content = text, status = status, createdAt = now)

    private fun pending(id: Long) =
        ChatMessageEntity(id = id, kind = "ASSISTANT", rawInput = input, status = "PENDING", createdAt = now)

    private fun renderer() = PromptRenderer(
        systemTemplate = asset("prompts/parse_bill_system.txt"),
        contextTemplate = asset("prompts/parse_bill_context.txt"),
        categories = emptyList(),
        context = ChatContext("2026-09-21 20:00（周一）", ZoneId.systemDefault().id, emptyList()),
        nickname = "路陌",
        suffix = "大人",
        candidates = PromptRenderer.CandidateBills(),
        pending = null,
        zone = ZoneId.systemDefault()
    )

    /** 截获发往 DeepSeek 的完整请求体 JSON。 */
    private fun captureWire(history: List<ChatTurn>): String {
        val captured = AtomicReference("")
        val respBody = """{"choices":[{"message":{"content":"{\"bills\":[],\"reply\":\"ok\"}"}}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            captured.set(buffer.readUtf8())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respBody.toResponseBody("application/json".toMediaType())).build()
        }.build()
        runBlocking {
            DeepSeekClient("test-key", client)
                .parseBill(renderer().renderSystem(), renderer().renderContext(input), history)
        }
        return captured.get()
    }

    /** 解析报文，把所有 message 的 content 拼起来（避免中文字符被 JSON 转义成 \uXXXX 干扰计数）。 */
    private fun allContents(wire: String): String =
        Json.parseToJsonElement(wire).jsonObject["messages"]!!.jsonArray
            .joinToString("\n") { it.jsonObject["content"]!!.jsonPrimitive.content }

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun `the in-flight input appears exactly once across the whole wire request`() {
        // 模拟 ChatViewModel.send(): 先落 USER、再落 PENDING，然后组装请求。
        val messages = listOf(
            user(1, "午饭 20"), assistant(2, "记好了"),
            user(3, input), pending(4)
        )
        val history = AiRepository.chatTurnsFor(messages, now)
        assertFalse("history 不得携带本轮输入", history.any { it.content == input })

        val wire = captureWire(history)
        assertEquals(
            "本轮输入在整条请求里只能出现一次（历史或动态段二选一，不能都有）",
            1, occurrences(allContents(wire), input)
        )
        // 反向确认它确实出现在动态段（而不是被整段丢掉）
        assertEquals("本轮输入必须仍出现在动态段", 1, occurrences(renderer().renderContext(input), input))
    }

    @Test
    fun `an earlier completed turn is carried once while the input stays single`() {
        val messages = listOf(
            user(1, "午饭 20"), assistant(2, "记好了"),
            user(3, input), pending(4)
        )
        val history = AiRepository.chatTurnsFor(messages, now)
        assertEquals(
            listOf("user" to "午饭 20", "assistant" to "记好了"),
            history.map { it.role to it.content }
        )
        val contents = allContents(captureWire(history))
        assertEquals("上一轮的旧回复应作为历史出现一次", 1, occurrences(contents, "记好了"))
        assertEquals("上一轮的旧用户话应作为历史出现一次", 1, occurrences(contents, "午饭 20"))
        assertEquals("本轮输入仍只出现一次", 1, occurrences(contents, input))
    }

    @Test
    fun `when the previous reply was interrupted the earlier user is still carried`() {
        val messages = listOf(
            user(1, "午饭 20"), assistant(2, "被打断了", status = "INTERRUPTED"),
            user(3, input), pending(4)
        )
        val history = AiRepository.chatTurnsFor(messages, now)
        assertEquals(listOf("user" to "午饭 20"), history.map { it.role to it.content })
        val contents = allContents(captureWire(history))
        assertEquals("更早那条未回复的 user 仍是历史", 1, occurrences(contents, "午饭 20"))
        assertEquals("本轮输入只出现一次", 1, occurrences(contents, input))
    }
}
