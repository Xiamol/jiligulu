package com.jiligulu.app.core.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** AI 返回的单条账单草稿 */
@Serializable
data class AiBillDraft(
    /**
     * 动作类型：add（新增）/ update（改已有）/ delete（删已有）/ restore（从回收站恢复）。
     * 未知值一律按 add 处理，保证老格式的返回仍可用。
     */
    val action: String = "add",
    /** update / delete 时指向目标账单 id；add 时为 0。 */
    @SerialName("target_id") val targetId: Long = 0,
    @SerialName("amount_yuan") val amountYuan: Double = 0.0,
    val type: String = "EXPENSE",
    val category: String = "",
    val detail: String = "",
    val note: String = "",
    @SerialName("is_new_category") val isNewCategory: Boolean = false,
    @SerialName("icon_emoji") val iconEmoji: String = "",
    @SerialName("icon_svg") val iconSvg: String = "",
    val keywords: String = "",
    @SerialName("time_expression") val timeExpression: String = "",
    @SerialName("occurred_at") val occurredAt: String = ""
) {
    val isAdd: Boolean get() = action.equals("add", true)
    val isUpdate: Boolean get() = action.equals("update", true)
    val isDelete: Boolean get() = action.equals("delete", true)
    val isRestore: Boolean get() = action.equals("restore", true)
}

/**
 * R4/R5：一个可点选项（跳转或进入追问）。
 *
 * 由模型在「用户没指定具体账单、只问怎么恢复」这类场景输出；点击语义由 [action] 决定。
 * [action] 取值只认 [NavTargets] / [IntentActions] 里定义的常量——UI 拿到未知值一律忽略、不崩，
 * 因为模型随口编一个跳转目标比「不给」更糟。
 */
@Serializable
data class AiOption(
    val label: String = "",
    /** NavTargets / IntentActions 取值；未知值一律忽略，不崩 */
    val action: String = ""
)

/**
 * 只有金额、没有名目的残缺输入（如「5」）。
 *
 * 按 coder 定的规则：不直接出草稿卡，而是挂起等下一句补全。
 * 模型把金额和它猜的可能名目报上来，本地决定是追问还是直接成草稿。
 */
@Serializable
data class AiPendingDraft(
    @SerialName("amount_yuan") val amountYuan: Double = 0.0,
    /** 模型猜的名目；空串表示它也不知道。 */
    val detail: String = "",
    val type: String = "EXPENSE",
    @SerialName("time_expression") val timeExpression: String = ""
)

@Serializable
data class AiParseResult(
    val bills: List<AiBillDraft> = emptyList(),
    val reply: String = "",
    /** 待补充：模型认为这句话只说了一半。 */
    val pending: AiPendingDraft? = null,
    /** R5：单目标跳转指令（NavTargets 取值）；不需要跳转时为 null。老响应缺此字段，默认兼容。 */
    val navigate: String? = null,
    /** R4：多选项卡，非空时优先于 navigate；不需要时为 []。老响应缺此字段，默认兼容。 */
    val options: List<AiOption> = emptyList()
)

/**
 * 把 HTTP 状态码翻成用户能看懂、且能据此行动的话。
 * 尤其 402「余额不足」和 401「Key 无效」必须分开——一个要充值，一个要换 Key，
 * 笼统说「连不上网」会让人往错误方向排查。
 */
class DeepSeekHttpException(val status: Int, val detail: String) : IOException("DeepSeek HTTP $status")

/** 一轮已有对话。[role] 只能是 "user" 或 "assistant"。 */
data class ChatTurn(val role: String, val content: String)

/**
 * DeepSeek 官方 API（OpenAI 兼容格式）。
 * 强制 response_format=json_object，本地再做 schema 解析兜底（PRD §8 风险 4）。
 */
class DeepSeekClient(private val apiKey: String, private val client: OkHttpClient = sharedClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 多轮对话解析。
     *
     * [history] 是**已有**对话（不含本轮），会原样作为 user/assistant 消息排在
     * system 之后、本轮输入之前。上一轮我们自己的回复必须是 assistant 角色、
     * 本轮输入必须是 user——这既让模型能承接「5 → 面条」这样的跨句补全，
     * 也让它能引用账本上下文闲聊（coder 要的「生活搭子」效果）。
     */
    suspend fun parseBill(
        systemPrompt: String,
        userInput: String,
        history: List<ChatTurn> = emptyList()
    ): Result<AiParseResult> =
        withContext(Dispatchers.IO) {
            try {
                val requestJson = buildJsonObject {
                    put("model", AiConfig.MODEL)
                    put("temperature", 0.7)
                    put("response_format", buildJsonObject { put("type", "json_object") })
                    put("messages", buildJsonArray {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                        // 历史被时间窗裁过之后，最早的几条可能是 assistant 轮（它对应的 user 轮滚出去了）。
                        // OpenAI 兼容接口要求首条必须是 user，所以把开头连续的 assistant 轮整体丢掉，
                        // 直到遇到第一个 user 为止。
                        history.dropWhile { it.role.equals("assistant", ignoreCase = true) }.forEach { turn ->
                            addJsonObject {
                                put("role", turn.role)
                                put("content", turn.content)
                            }
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", userInput)
                        }
                    })
                }.toString()

                val request = Request.Builder()
                    .url(AiConfig.BASE_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val parsed = client.newCall(request).awaitResponse().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw DeepSeekHttpException(response.code, body.take(400))
                    }
                    val root = json.parseToJsonElement(body).jsonObject
                    logCacheUsage(root)
                    val content = root["choices"]!!.jsonArray[0]
                        .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                    json.decodeFromString(AiParseResult.serializer(), unwrapJsonFence(content))
                }
                Result.success(parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    companion object {
        private const val TAG = "DeepSeekClient"

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    /**
     * R9 的唯一可验收指标：本轮 prompt 的缓存命中 / 未命中 token 数。
     *
     * 只打日志、绝不改行为——命中率长期为 0 就说明 system 段被动态内容污染了，
     * 或者历史又被每轮重排，看这行日志能第一时间发现。
     */
    private fun logCacheUsage(root: JsonObject) {
        val usage = root["usage"]?.jsonObject ?: return
        val hit = usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.contentOrNull
        val miss = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.contentOrNull
        Log.d(TAG, "prompt cache: hit=$hit miss=$miss")
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/**
 * 兜底：即使开了 json_object，模型偶尔仍会用 ```json ... ``` 包裹。
 * 这里统一剥掉围栏再交给序列化器，避免解析失败。
 */
private fun unwrapJsonFence(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
