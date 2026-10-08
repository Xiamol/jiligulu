package com.jiligulu.app.core.ai

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class AiUsagePurpose(val label: String) {
    LEDGER_CHAT("记账聊天"), IMAGE_RECOGNITION("图片识别"), CLASSIFICATION("分类建议"),
    HEART_LETTER("心事回信"), LIU_REN("小六壬分析"), UNSPECIFIED("用途未知")
}

/** CNY per million tokens, stored as micro-CNY so multiplication yields exact pico-CNY. */
data class AiPriceSnapshot(val version: String, val cacheRateMicros: Long, val missRateMicros: Long,
    val outputRateMicros: Long) {
    init { require(listOf(cacheRateMicros, missRateMicros, outputRateMicros).all { it in 0..1_000_000_000_000L }) }
    companion object {
        val userDeepSeekDefault = AiPriceSnapshot("user-deepseek-flash-0.02-1-4-v1", 20_000, 1_000_000, 4_000_000)
        fun configured(cache: String, miss: String, output: String): AiPriceSnapshot {
            fun rate(value: String): Long {
                val number = value.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("请填写有效的人民币单价")
                require(number >= BigDecimal.ZERO && number <= BigDecimal("1000000") && number.stripTrailingZeros().scale() <= 6) {
                    "单价应为 0–1000000 元，最多六位小数"
                }
                return number.movePointRight(6).longValueExact()
            }
            return AiPriceSnapshot(UUID.randomUUID().toString(), rate(cache), rate(miss), rate(output))
        }
    }
}

object AiCostUnknown {
    const val PRICE = 1
    const val USAGE = 2
    const val INPUT = 4
    const val OUTPUT = 8
    const val CACHE_PRICE = 16
    const val RANGE = 32
    const val LEGACY = 64
}

data class AiCostEstimate(val cachePico: Long?, val missPico: Long?, val outputPico: Long?,
    val flatInputPico: Long?, val unknownFlags: Int) {
    val knownPico: Long? get() {
        val pieces = listOfNotNull(cachePico, missPico, outputPico, flatInputPico)
        if (pieces.isEmpty()) return null
        return runCatching { pieces.fold(0L, Math::addExact) }.getOrNull()
    }
    val incomplete: Boolean get() = unknownFlags != 0 || knownPico == null
    companion object {
        fun calculate(usage: AiTokenUsage?, price: AiPriceSnapshot?): AiCostEstimate {
            if (usage == null) return AiCostEstimate(null, null, null, null,
                AiCostUnknown.USAGE or if (price == null) AiCostUnknown.PRICE else 0)
            if (price == null) return AiCostEstimate(null, null, null, null, AiCostUnknown.PRICE)
            var flags = 0
            if (!usage.inputReported) flags = flags or AiCostUnknown.INPUT
            if (!usage.outputReported) flags = flags or AiCostUnknown.OUTPUT
            fun multiply(tokens: Long, rate: Long): Long? = runCatching { Math.multiplyExact(tokens, rate) }
                .getOrElse { flags = flags or AiCostUnknown.RANGE; null }
            val cache = if (usage.cacheReported && usage.cacheHitReported) multiply(usage.cacheHit, price.cacheRateMicros) else null
            val miss = if (usage.cacheReported && usage.cacheMissReported) multiply(usage.cacheMiss, price.missRateMicros) else null
            val output = if (usage.outputReported) multiply(usage.output, price.outputRateMicros) else null
            val flat = if (usage.unclassifiedInput > 0) {
                if (price.cacheRateMicros == price.missRateMicros) multiply(usage.unclassifiedInput, price.missRateMicros)
                else { flags = flags or AiCostUnknown.CACHE_PRICE; null }
            } else null
            return AiCostEstimate(cache, miss, output, flat, flags).let {
                if (listOfNotNull(cache, miss, output, flat).isNotEmpty() && it.knownPico == null)
                    it.copy(unknownFlags = flags or AiCostUnknown.RANGE) else it
            }
        }
    }
}

data class AiUsageTicket(val id: String, val providerKey: String, val providerGroup: String,
    val providerName: String, val model: String, val purpose: AiUsagePurpose, val startedAt: Long,
    val day: String, val price: AiPriceSnapshot?) {
    companion object {
        fun start(profile: AiProviderProfile, purpose: AiUsagePurpose, price: AiPriceSnapshot?, now: Long) =
            AiUsageTicket(UUID.randomUUID().toString(), providerKey(profile), providerGroup(profile),
                profile.name, profile.model, purpose, now,
                Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString(), price)
        fun providerKey(profile: AiProviderProfile): String {
            // Endpoint hash distinguishes differently priced custom services without storing URLs or keys.
            val identity = profile.usageId + "\n" + profile.model + "\n" + if (profile.id == AiProviderId.CUSTOM) profile.endpoint else "deepseek"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun providerGroup(profile: AiProviderProfile): String = if (profile.id == AiProviderId.DEEPSEEK) "deepseek"
            else "custom:" + MessageDigest.getInstance("SHA-256").digest(profile.endpoint.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

interface AiUsageMeter {
    suspend fun begin(profile: AiProviderProfile, purpose: AiUsagePurpose): AiUsageTicket
    suspend fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?)
}

fun picoYuanText(value: Long): String = BigDecimal.valueOf(value, 12).stripTrailingZeros().toPlainString()
