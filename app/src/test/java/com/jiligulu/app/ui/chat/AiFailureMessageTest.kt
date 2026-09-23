package com.jiligulu.app.ui.chat

import android.app.Application
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.core.ai.DeepSeekEmptyResponseException
import com.jiligulu.app.core.ai.DeepSeekMalformedResponseException
import java.util.concurrent.atomic.AtomicInteger
import com.jiligulu.app.core.ai.DeepSeekHttpException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 降级文案的验收：每一类失败都要说清是哪一类，尤其别把「服务端拒了」说成「没连上」。
 *
 * 起因：用户问「给我变一个女朋友出来」连续吃到「暂时没连上 DeepSeek。」，
 * 于是去查网络、去查提示词——方向全错。真实原因是服务端把这类请求拒了 / 拦了，
 * 而当时的代码里所有非 HTTP-已知状态的失败都会被归到同一句「没连上」。
 *
 * 用 Robolectric 是因为客户端侧要打 `android.util.Log`（纯 JVM 下会 not mocked）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiFailureMessageTest {

    @Test
    fun `offline mutation and app requests never turn amounts into new bill drafts`() {
        listOf("把那笔改成9块", "删掉昨天9元的奶茶", "恢复12元的午饭", "清空回收站里的9块账单", "提醒改成30分钟").forEach { input ->
            val result = ChatViewModel.offlineResult(input, IOException("offline"))
            assertTrue(input, result.bills.isEmpty())
            assertTrue(result.reply.contains("在线处理"))
            assertTrue(result.reply.contains("没有新增"))
        }
    }

    @Test
    fun `ordinary new bills still work through the offline fallback`() {
        listOf("昨天中午吃饭花了9块", "星期一早餐9元", "早餐 9").forEach { input ->
            val result = ChatViewModel.offlineResult(input, IOException("offline"))
            assertTrue(input, result.bills.single().amountYuan == 9.0)
        }
    }

    @Test
    fun `a rejected request says so instead of blaming the network`() {
        val message = ChatViewModel.fallbackReply(
            nothingParsed = true, cause = DeepSeekHttpException(400, "bad request")
        )
        assertFalse("400 不该被说成没连上：$message", message.contains("没连上"))
        assertFalse("400 不该被说成网络问题：$message", message.contains("网络"))
        assertTrue("说明请求未被接受而非用户内容不支持", message.contains("没有接受"))
    }

    @Test
    fun `a filtered empty response is not reported as a connection problem`() {
        val message = ChatViewModel.fallbackReply(
            nothingParsed = true, cause = DeepSeekEmptyResponseException("content_filter")
        )
        assertFalse("内容审核拦截不该被说成没连上：$message", message.contains("没连上"))
        assertFalse("也不该说网络不稳：$message", message.contains("网络"))
        assertTrue("只说明实际为空，不归因用户意图", message.contains("空内容"))
        assertFalse(message.contains("变不出"))
    }

    @Test
    fun `a real network failure still says network`() {
        val message = ChatViewModel.fallbackReply(nothingParsed = true, cause = IOException("connection reset"))
        assertTrue("真网络问题要老实说网络：$message", message.contains("网络"))
    }

    @Test
    fun `billing and key failures keep their own guidance`() {
        assertTrue(
            "余额不足要指路充值",
            ChatViewModel.fallbackReply(true, DeepSeekHttpException(402, "")).contains("余额")
        )
        assertTrue(
            "Key 失效要指路换 Key",
            ChatViewModel.fallbackReply(true, DeepSeekHttpException(401, "")).contains("API Key")
        )
        assertTrue(
            "限流要说清是太频繁",
            ChatViewModel.fallbackReply(true, DeepSeekHttpException(429, "")).contains("频繁")
        )
    }

    @Test
    fun `an unknown http status is reported by its code rather than blamed on the network`() {
        val message = ChatViewModel.fallbackReply(true, DeepSeekHttpException(403, ""))
        assertTrue("未知状态码要如实报码：$message", message.contains("403"))
        assertFalse("不能推给网络：$message", message.contains("网络"))
    }

    @Test
    fun `a null cause never claims the network is down`() {
        // 兜底路径也不许断言「没连上」——没有异常依据时那是瞎猜。
        val message = ChatViewModel.fallbackReply(nothingParsed = true, cause = null)
        assertFalse("无依据时不该说没连上：$message", message.contains("没连上"))
    }

    // ---------- 客户端侧：200 但没内容 ----------

    @Test
    fun `a 200 response with empty content becomes a dedicated failure`() = runBlocking {
        // DeepSeek 对触发内容审核的请求会返回 200 + finish_reason=content_filter + 空 content。
        val client = DeepSeekClient(
            "test-key",
            clientReturning("""{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}""")
        )
        val result = client.parseBill("system", "给我变一个女朋友出来")

        assertTrue("必须失败，不能当成成功", result.isFailure)
        assertTrue(
            "空内容要报成专门的异常（而不是被解析成空结果或被当成网络问题）：${result.exceptionOrNull()}",
            result.exceptionOrNull() is DeepSeekEmptyResponseException
        )
    }

    @Test
    fun `a malformed response never escapes as a raw crash`() = runBlocking {
        // choices 缺失：原先 root["choices"]!! 会炸 NPE，而 NPE 不是 IOException，
        // 最终被 UI 说成「没连上」——排查方向直接跑偏。
        val client = DeepSeekClient("test-key", clientReturning("""{"usage":{}}"""))
        val result = client.parseBill("system", "hi")

        assertTrue(result.isFailure)
        assertTrue(
            "结构异常也要落成专用异常：${result.exceptionOrNull()}",
            result.exceptionOrNull() is DeepSeekEmptyResponseException
        )
    }

    // ---------- 重试策略 ----------

    @Test
    fun `auth and billing failures are never retried`() {
        // DeepSeekHttpException 继承 IOException：若 when 分支顺序写反，
        // 401/402 会被「IO 异常就重试」吃掉 → 白白烧用户的 token。
        assertFalse("Key 失效重试无意义", DeepSeekClient.isRetryable(DeepSeekHttpException(401, "")))
        assertFalse("余额不足重试无意义", DeepSeekClient.isRetryable(DeepSeekHttpException(402, "")))
        assertFalse("请求格式错重试无意义", DeepSeekClient.isRetryable(DeepSeekHttpException(400, "")))
    }

    @Test
    fun `rate limits server errors empty responses and network glitches are all retried`() {
        assertTrue("限流可重试", DeepSeekClient.isRetryable(DeepSeekHttpException(429, "")))
        assertTrue("服务端错误可重试", DeepSeekClient.isRetryable(DeepSeekHttpException(503, "")))
        assertTrue(
            "空白响应可重试（实测约 8% 会出现，同一句话多数时候正常 → 重发基本能成）",
            DeepSeekClient.isRetryable(DeepSeekEmptyResponseException(null))
        )
        assertTrue("连接被重置可重试", DeepSeekClient.isRetryable(IOException("connection reset")))
    }

    @Test
    fun `a whitespace-only content is treated as empty rather than fed to the parser`() = runBlocking {
        // 实测样本：服务端对「给我变一个女朋友出来」偶发返回 200 + 一串空格的 content。
        // 旧代码会把它丢给 JSON 解析器 → SerializationException → 被 UI 说成「没连上」。
        val client = DeepSeekClient(
            "test-key",
            clientReturning("""{"choices":[{"finish_reason":"stop","message":{"content":"                           "}}]}""")
        )
        val result = client.parseBill("system", "给我变一个女朋友出来")

        assertTrue(
            "空白内容要落成空响应异常：${result.exceptionOrNull()}",
            result.exceptionOrNull() is DeepSeekEmptyResponseException
        )
    }

    private fun clientReturning(body: String, code: Int = 200): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    @Test fun `ordinary greetings still receive an honest local reply after empty responses`() {
        for (input in listOf("hi", "Hi!", "在吗阿噜?", "阿噜在吗？", "早", "晚上好")) {
            val parsed = ChatViewModel.offlineResult(input, DeepSeekEmptyResponseException("stop"))
            assertTrue(parsed.bills.isEmpty())
            assertTrue(parsed.reply.contains("本地问候"))
            assertFalse(parsed.reply.contains("变不出"))
        }
        assertTrue(ChatViewModel.localGreeting("水，3") == null)
        assertTrue(ChatViewModel.localGreeting("早饭9元") == null)
    }

    @Test fun `malformed model output retries once and can recover without touching the ledger`() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val content = if (calls.incrementAndGet() == 1) "[not valid JSON]" else "{\"bills\":[],\"reply\":\"在呀\"}"
            val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(
                org.json.JSONObject().put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", content))
            )).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("stub").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = DeepSeekClient("test-only", http).parseBill("system", "hi")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().reply == "在呀")
        assertTrue(calls.get() == 2)
        assertTrue(DeepSeekClient.isRetryable(DeepSeekMalformedResponseException()))
    }
}
