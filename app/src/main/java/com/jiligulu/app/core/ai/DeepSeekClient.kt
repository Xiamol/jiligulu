package com.jiligulu.app.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
)

@Serializable
data class AiParseResult(
    val bills: List<AiBillDraft> = emptyList(),
    val reply: String = ""
)

/**
 * DeepSeek 官方 API（OpenAI 兼容格式）。
 * 强制 response_format=json_object，本地再做 schema 解析兜底（PRD §8 风险 4）。
 */
class DeepSeekClient(private val apiKey: String, private val client: OkHttpClient = sharedClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseBill(systemPrompt: String, userInput: String): Result<AiParseResult> =
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
                    check(response.isSuccessful) { "DeepSeek HTTP ${response.code}" }
                    val body = response.body?.string().orEmpty()
                    val content = json.parseToJsonElement(body)
                        .jsonObject["choices"]!!.jsonArray[0]
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
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
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
