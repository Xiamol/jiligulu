package com.jiligulu.app.core.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
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
     * 缺省兼容老格式的新增；明确给出的未知动作不会执行。
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

/** Read-only, bound-parameter lookup. Dates use the device's local calendar; end is exclusive. */
@Serializable
data class AiLedgerQuery(
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    val keywords: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val type: String = ""
)

@Serializable
data class AiMemoryUpdate(val kind: String = "", val value: String = "", val evidence: String = "")

@Serializable
data class AiParseResult(
    val bills: List<AiBillDraft> = emptyList(),
    val reply: String = "",
    /** 待补充：模型认为这句话只说了一半。 */
    val pending: AiPendingDraft? = null,
    /** R5：单目标跳转指令（NavTargets 取值）；不需要跳转时为 null。老响应缺此字段，默认兼容。 */
    val navigate: String? = null,
    /** R4：多选项卡，非空时优先于 navigate；不需要时为 []。老响应缺此字段，默认兼容。 */
    val options: List<AiOption> = emptyList(),
    /** App settings / trash operations only create a local confirmation card. */
    @SerialName("app_action") val appAction: AiAppAction? = null,
    @SerialName("ledger_query") val ledgerQuery: AiLedgerQuery? = null,
    @SerialName("memory_updates") @Serializable(with = AiMemoryUpdatesSerializer::class)
    val memoryUpdates: List<AiMemoryUpdate> = emptyList(),
    /** Local provenance only. The model cannot grant itself permission to edit arbitrary IDs. */
    @kotlinx.serialization.Transient val retrievedBillIds: Set<Long> = emptySet(),
    @kotlinx.serialization.Transient val ledgerLookupCompleted: Boolean = false
)

/**
 * 把 HTTP 状态码翻成用户能看懂、且能据此行动的话。
 * 尤其 402「余额不足」和 401「Key 无效」必须分开——一个要充值，一个要换 Key，
 * 笼统说「连不上网」会让人往错误方向排查。
 */
class DeepSeekHttpException(val status: Int, val detail: String, providerName: String = "DeepSeek") : IOException("$providerName HTTP $status")

/**
 * 服务端返回了 200，但没有给出任何内容。
 *
 * Empty content also occurs with finish_reason=stop during ordinary greetings.
 * The reason alone does not establish censorship, unsupported intent, or a Wi-Fi problem.
 */
class DeepSeekEmptyResponseException(val finishReason: String?) :
    IllegalStateException("AI 服务返回空内容（finish_reason=${finishReason ?: "未知"}）")

class DeepSeekMalformedResponseException : IllegalStateException("AI 返回的内容格式不正确")

/** 一轮已有对话。[role] 只能是 "user" 或 "assistant"。 */
data class ChatTurn(val role: String, val content: String)

/**
 * OpenAI-compatible chat completion transport; the legacy class name stays source-compatible.
 * Provider capabilities decide optional parameters; local schema checks apply to every provider.
 */
class DeepSeekClient private constructor(
    private val apiKey: String,
    private val client: OkHttpClient,
    val profile: AiProviderProfile,
    private val onUsage: suspend (AiTokenUsage?) -> Unit,
    private val meter: AiUsageMeter? = null,
    private val usagePurpose: AiUsagePurpose = AiUsagePurpose.UNSPECIFIED,
) {
    constructor(apiKey: String, client: OkHttpClient = sharedClient, onUsage: suspend (AiTokenUsage?) -> Unit = {}) :
        this(apiKey, client, AiProviderProfile(), onUsage)
    constructor(apiKey: String, profile: AiProviderProfile, client: OkHttpClient = sharedClient,
        onUsage: suspend (AiTokenUsage?) -> Unit = {}) : this(apiKey, client, profile, onUsage)

    fun configuredFor(profile: AiProviderProfile): DeepSeekClient = DeepSeekClient(apiKey, client, profile, onUsage, meter, usagePurpose)
    fun forPurpose(purpose: AiUsagePurpose): DeepSeekClient = DeepSeekClient(apiKey, client, profile, onUsage, meter, purpose)
    fun meteredBy(meter: AiUsageMeter): DeepSeekClient = DeepSeekClient(apiKey, client, profile, onUsage, meter, usagePurpose)

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
        history: List<ChatTurn> = emptyList(),
        stableContext: String = "",
        purpose: AiUsagePurpose = usagePurpose,
    ): Result<AiParseResult> = withContext(Dispatchers.IO) {
        var lastFailure: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return@withContext Result.success(executeOnce(systemPrompt, userInput, history, stableContext, purpose))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt < MAX_ATTEMPTS && isRetryable(failure)) {
                    // 移动网络下「上一句还好、下一句就断」多半是 keep-alive 连接被静默回收，
                    // 重发一次通常就好——这是用户能直接感知到的最大改善。
                    Log.w(TAG, "请求重试：attempt=$attempt cause=${failure.javaClass.simpleName}")
                    delay(RETRY_DELAY_MS)
                } else {
                    // Logs retain error classes only; provider bodies/URLs can echo credentials.
                    Log.w(TAG, "请求失败：${failure.javaClass.simpleName}")
                    return@withContext Result.failure(failure)
                }
            }
        }
        Result.failure(lastFailure ?: IllegalStateException("AI 服务请求失败"))
    }

    /** 单次请求：组装 → 发送 → 解析。重试策略与失败日志都在 [parseBill]。 */
    private suspend fun executeOnce(
        systemPrompt: String,
        userInput: String,
        history: List<ChatTurn>,
        stableContext: String,
        purpose: AiUsagePurpose,
    ): AiParseResult {
        val content = executeContent(textRequest(systemPrompt, userInput, history, stableContext, OUTPUT_CONTRACT), purpose)
        return try {
            json.decodeFromString(AiParseResult.serializer(), unwrapJsonFence(content))
        } catch (parseFailure: Exception) {
            Log.w(TAG, "回复格式校验失败：${parseFailure.javaClass.simpleName}，长度=${content.length}")
            throw DeepSeekMalformedResponseException()
        }
    }

    /** Structured side features choose one attempt; bad content never silently buys a repair. */
    suspend fun requestJson(systemPrompt: String, userInput: String,
        purpose: AiUsagePurpose = usagePurpose): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val content = executeContent(textRequest(systemPrompt, userInput, emptyList(), "",
                "\n请只返回 system 中指定的 JSON 对象。"), purpose)
            json.parseToJsonElement(unwrapJsonFence(content)) as? JsonObject ?: throw DeepSeekMalformedResponseException()
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun textRequest(systemPrompt: String, userInput: String, history: List<ChatTurn>,
        stableContext: String, contract: String): String = buildJsonObject {
            put("model", profile.model)
            if (profile.sendsTemperature) put("temperature", 0.7)
            if (profile.disablesDeepSeekThinking) put("thinking", buildJsonObject { put("type", "disabled") })
            if (profile.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                if (stableContext.isNotBlank()) addJsonObject {
                    put("role", "system")
                    put("content", stableContext)
                }
                // 历史被时间窗裁过之后，最早的几条可能是 assistant 轮（它对应的 user 轮滚出去了）。
                // OpenAI 兼容接口要求首条必须是 user，所以把开头连续的 assistant 轮整体丢掉，
                // 直到遇到第一个 user 为止。
                history.dropWhile { it.role.equals("assistant", ignoreCase = true) }.forEach { turn ->
                    addJsonObject {
                        put("role", turn.role)
                        put("content", historyContent(turn))
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userInput + contract)
                }
            })
        }.toString()

    /** The same selected profile, credentials, usage observer and cancellation apply to images. */
    suspend fun recognizeImage(systemPrompt: String, requestContext: String, jpegBase64: String,
        purpose: AiUsagePurpose = AiUsagePurpose.IMAGE_RECOGNITION): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(profile.supportsImages) { "当前模型未启用图片输入，请在「AI 服务」编辑供应商的图像能力，或选择支持识图的模型" }
            val request = buildJsonObject {
                put("model", profile.model)
                if (profile.supportsLegacyTokenLimit) put("max_tokens", 2400)
                if (profile.sendsTemperature) put("temperature", 0.0)
                if (profile.disablesDeepSeekThinking) put("thinking", buildJsonObject { put("type", "disabled") })
                if (profile.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
                put("messages", buildJsonArray {
                    addJsonObject { put("role", "system"); put("content", systemPrompt) }
                    addJsonObject { put("role", "user"); put("content", buildJsonArray {
                        addJsonObject { put("type", "text"); put("text", requestContext) }
                        addJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$jpegBase64") }) }
                    }) }
                })
            }.toString()
            unwrapJsonFence(executeContent(request, purpose))
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun executeContent(requestJson: String, purpose: AiUsagePurpose): String {
        val builder = Request.Builder().url(profile.endpoint)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val request = builder.post(requestJson.toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        // Each real attempt fixes its provider/model/price at start. Parsing never records again.
        val ticket = try { meter?.begin(profile, purpose) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }

        var reportedUsage: AiTokenUsage? = null
        try {
            return call.awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                reportedUsage = parsed?.let { AiTokenUsage.fromResponse(it) }
                if (!response.isSuccessful) {
                    throw DeepSeekHttpException(response.code, redact(body).take(400), profile.name)
                }
                val root = parsed ?: throw DeepSeekMalformedResponseException()
                logCacheUsage(root)
                // 逐步取，不用 !!：DeepSeek 对触发内容审核的请求会返回 200 但内容为空
                // （finish_reason = content_filter），也有过 choices 为空的形态。
                // 用 !! 会炸成 NPE / 下标越界，最后被 UI 当成「没连上」——那是误导。
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let { redact(it).take(80) }
                val rawContent = choice?.get("message")?.jsonObject?.get("content")
                val content = when (rawContent) {
                    is kotlinx.serialization.json.JsonPrimitive -> rawContent.contentOrNull
                    is JsonArray -> rawContent.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
                    else -> null
                }
                if (content.isNullOrBlank()) {
                    Log.w(TAG, "响应没有内容：finish_reason=$finishReason")
                    throw DeepSeekEmptyResponseException(finishReason)
                }
                content
            }
        } finally {
            // Count reported usage even when content is empty/malformed and this attempt retries.
            // The optional observer is metadata-only and must never trigger another API call.
            withContext(kotlinx.coroutines.NonCancellable) {
                try { onUsage(reportedUsage) } catch (_: Exception) {
                    Log.w(TAG, "Local usage observer failed")
                }
                if (ticket != null) try { meter?.finish(ticket, reportedUsage) } catch (_: Exception) {
                    Log.w(TAG, "Local price meter could not be saved")
                }
            }
        }
    }

    private fun redact(value: String): String = if (apiKey.isBlank()) value else value.replace(apiKey, "[redacted]")

    companion object {
        // JSON output and plain-text assistant exemplars conflict in multi-turn conversations.
        // Only the wire representation changes; persisted history is never rewritten or dropped.
        internal fun historyContent(turn: ChatTurn): String =
            if (turn.role.equals("assistant", ignoreCase = true)) buildJsonObject {
                put("bills", buildJsonArray { })
                put("reply", turn.content)
            }.toString() else turn.content

        internal const val OUTPUT_CONTRACT = "\n请只返回符合应用协议的 JSON 对象；闲聊示例：{\"bills\":[],\"reply\":\"在呀，阿噜在听。\"}。不要返回空白、代码或协议说明。"
        private const val TAG = "DeepSeekClient"

        /** 首次 + 重试 1 次。移动网络下这一次重发就能救回绝大多数「偶发连不上」。 */
        private const val MAX_ATTEMPTS = 2

        /** 重试前的短暂退避：足够让连接池换一条新连接，又不至于让用户等太久。 */
        private const val RETRY_DELAY_MS = 600L

        /**
         * 只有「重试有意义」的失败才重发。
         *
         * ⚠️ **顺序要紧**：[DeepSeekHttpException] 继承自 [IOException]，
         * 若把 `is IOException` 写在前面，401（Key 失效）/402（余额不足）这类
         * 「重试多少次都一样」的失败也会被重发，白白烧用户的 token。
         *
         * - 429 / 5xx：限流或服务端打喷嚏 → 重发
         * - [DeepSeekEmptyResponseException]：实测约 8% 的请求（尤其「给我变个女朋友」这种）
         *   会返回 200 + 空白内容，而同一句话多数时候是正常回答 → 重发基本能拿到结果
         * - 其他 [IOException]：连接被重置、超时、DNS 抖动 → 重发
         * - 401 / 402 / 400：重试无意义
         */
        internal fun isRetryable(failure: Exception): Boolean = when (failure) {
            is DeepSeekHttpException -> failure.status == 429 || failure.status >= 500
            is DeepSeekEmptyResponseException -> true
            is DeepSeekMalformedResponseException -> true
            is IOException -> true
            else -> false
        }

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
        val usage = root["usage"] as? JsonObject ?: return
        val hit = (usage["prompt_cache_hit_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val miss = (usage["prompt_cache_miss_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val hits = hit?.toLongOrNull() ?: 0L
        val misses = miss?.toLongOrNull() ?: 0L
        val ratio = if (hits + misses > 0) "%.1f%%".format(java.util.Locale.ROOT, hits * 100.0 / (hits + misses)) else "unknown"
        Log.d(TAG, "prompt cache: hit=${hit?.toLongOrNull()} miss=${miss?.toLongOrNull()} rate=$ratio")
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
