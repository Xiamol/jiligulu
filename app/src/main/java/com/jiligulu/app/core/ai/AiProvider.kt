package com.jiligulu.app.core.ai

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable enum class AiProviderId { DEEPSEEK, CUSTOM }
@Serializable enum class AiEndpointKind { BASE_URL, CHAT_ENDPOINT }

/** Public configuration only: credentials are kept separately and never part of this DTO. */
@Serializable
data class AiProviderProfile(
    val id: AiProviderId = AiProviderId.DEEPSEEK,
    val name: String = "DeepSeek",
    val address: String = AiConfig.BASE_URL,
    val endpointKind: AiEndpointKind = AiEndpointKind.CHAT_ENDPOINT,
    val model: String = AiConfig.MODEL,
    val supportsImages: Boolean = true,
    val jsonMode: Boolean = true,
    val sendsTemperature: Boolean = true,
) {
    val endpoint: String get() = AiProviderEndpoint.resolve(address, endpointKind)
    val usageId: String get() = if (id == AiProviderId.DEEPSEEK && model != AiConfig.MODEL) "deepseek:$model" else id.name.lowercase()
    val disablesDeepSeekThinking: Boolean get() = id == AiProviderId.DEEPSEEK
    val supportsLegacyTokenLimit: Boolean get() = id == AiProviderId.DEEPSEEK

    companion object {
        fun deepSeek(model: String = AiConfig.MODEL) = AiProviderProfile(model = model, supportsImages = model == AiConfig.MODEL)
        fun custom() = AiProviderProfile(id = AiProviderId.CUSTOM, name = "自定义供应商", address = "",
            endpointKind = AiEndpointKind.BASE_URL, model = "", supportsImages = false,
            jsonMode = false, sendsTemperature = false)
    }
}

/** Deliberately not a data class: its string representation cannot reveal a key. */
class AiProviderConnection(val profile: AiProviderProfile, val apiKey: String) {
    override fun toString() = "AiProviderConnection(${profile.id}, credentials=redacted)"
}

object AiProviderEndpoint {
    fun resolve(value: String, kind: AiEndpointKind): String {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请填写有效的 HTTP 或 HTTPS 地址")
        require(url.username.isEmpty() && url.password.isEmpty()) { "地址中不能包含账号或密钥，请将密钥填写到独立密钥栏" }
        require(url.fragment == null) { "接口地址不能包含 # 片段" }
        if (kind == AiEndpointKind.CHAT_ENDPOINT || url.encodedPath.trimEnd('/').endsWith("/chat/completions")) return url.toString()
        val builder = url.newBuilder()
        if (url.encodedPath.trim('/') == "") builder.addPathSegment("v1")
        builder.addPathSegment("chat").addPathSegment("completions")
        return builder.build().toString()
    }

    fun validate(profile: AiProviderProfile) {
        require(profile.name.isNotBlank() && profile.name.length <= 60) { "供应商名称请填写 1–60 个字符" }
        require(profile.model.isNotBlank() && profile.model.length <= 160 && profile.model.none { it.isISOControl() }) { "请填写供应商提供的模型 ID" }
        require(profile.address.length <= 2_048) { "接口地址过长" }
        resolve(profile.address, profile.endpointKind)
    }
}
