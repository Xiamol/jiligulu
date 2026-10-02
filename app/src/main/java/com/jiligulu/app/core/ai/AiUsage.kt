package com.jiligulu.app.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Numeric metadata only. Completion tokens already include any provider reasoning tokens. */
@Serializable
data class AiTokenUsage(
    val cacheHit: Long = 0,
    val cacheMiss: Long = 0,
    val unclassifiedInput: Long = 0,
    val output: Long = 0
) {
    companion object {
        fun fromResponse(root: JsonObject): AiTokenUsage? {
            val usage = root["usage"] as? JsonObject ?: return null
            fun count(key: String): Long? = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            val input = count("prompt_tokens")
            val output = count("completion_tokens")
            val reportedHit = count("prompt_cache_hit_tokens")
                ?: ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            val reportedMiss = count("prompt_cache_miss_tokens")
            if (input == null && output == null && reportedHit == null && reportedMiss == null) return null
            val hit = reportedHit?.coerceAtMost(input ?: Long.MAX_VALUE) ?: 0L
            val miss = (reportedMiss ?: if (input != null && reportedHit != null) input - hit else 0L)
                .coerceAtMost(input?.minus(hit) ?: Long.MAX_VALUE)
            return AiTokenUsage(hit, miss, ((input ?: 0) - hit - miss).coerceAtLeast(0), output ?: 0)
        }
    }
}

@Serializable
data class AiUsageTotals(
    val calls: Long = 0,
    val reportedCalls: Long = 0,
    val cacheHit: Long = 0,
    val cacheMiss: Long = 0,
    val unclassifiedInput: Long = 0,
    val output: Long = 0
) {
    val knownInput: Long get() = cacheHit + cacheMiss
    val hitRate: Double? get() = if (knownInput > 0) cacheHit.toDouble() / knownInput else null
    // User-supplied yuan per million tokens, intentionally not a claim about provider billing.
    val estimatedYuan: Double get() = (cacheHit * .02 + (cacheMiss + unclassifiedInput) * 1.0 + output * 4.0) / 1_000_000
    fun add(usage: AiTokenUsage?) = copy(
        calls = calls + 1,
        reportedCalls = reportedCalls + if (usage != null) 1 else 0,
        cacheHit = cacheHit + (usage?.cacheHit ?: 0),
        cacheMiss = cacheMiss + (usage?.cacheMiss ?: 0),
        unclassifiedInput = unclassifiedInput + (usage?.unclassifiedInput ?: 0),
        output = output + (usage?.output ?: 0)
    )
}

/** Two bounded aggregates; never stores request ids, text, images, keys or per-request logs. */
@Serializable
data class AiUsageSnapshot(
    val since: String = "",
    val day: String = "",
    val today: AiUsageTotals = AiUsageTotals(),
    val total: AiUsageTotals = AiUsageTotals()
) {
    fun forDay(date: String): AiUsageTotals = if (day == date) today else AiUsageTotals()
    fun add(date: String, usage: AiTokenUsage?) = copy(
        since = since.ifEmpty { date }, day = date,
        today = forDay(date).add(usage), total = total.add(usage)
    )
}
