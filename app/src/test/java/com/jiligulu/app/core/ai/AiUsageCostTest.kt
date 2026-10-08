package com.jiligulu.app.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AiUsageCostTest {
    private val price = AiPriceSnapshot.userDeepSeekDefault
    private fun usage(body: String) = requireNotNull(AiTokenUsage.fromResponse(Json.parseToJsonElement(body).jsonObject))
    private fun flagged(value: AiCostEstimate, flag: Int) = value.unknownFlags and flag != 0

    @Test fun userRatesAreExactCnyPerMillionRatherThanFloatingPointOrPerTokenRates() {
        val result = AiCostEstimate.calculate(AiTokenUsage(1_000_000, 1_000_000, 0, 1_000_000), price)
        assertEquals(20_000_000_000L, result.cachePico)
        assertEquals(1_000_000_000_000L, result.missPico)
        assertEquals(4_000_000_000_000L, result.outputPico)
        assertEquals(5_020_000_000_000L, result.knownPico)
        assertEquals("5.02", picoYuanText(requireNotNull(result.knownPico)))
        assertFalse(result.incomplete)
    }

    @Test fun missingCacheSplitKeepsOnlyOutputSubtotalInsteadOfPricingAllInputAsMisses() {
        val result = AiCostEstimate.calculate(usage("""{"usage":{"prompt_tokens":1000,"completion_tokens":100}}"""), price)
        assertNull(result.cachePico); assertNull(result.missPico); assertNull(result.flatInputPico)
        assertEquals(400_000_000L, result.outputPico)
        assertEquals(400_000_000L, result.knownPico)
        assertTrue(flagged(result, AiCostUnknown.CACHE_PRICE)); assertTrue(result.incomplete)
    }

    @Test fun onlyReportedMissesNeverTurnUnreportedCacheHitsIntoAZeroCost() {
        val withTotal = usage("""{"usage":{"prompt_tokens":1000,"prompt_cache_miss_tokens":200,"completion_tokens":3}}""")
        assertFalse(withTotal.cacheHitReported); assertTrue(withTotal.cacheMissReported)
        val result = AiCostEstimate.calculate(withTotal, price)
        assertNull(result.cachePico)
        assertEquals(200_000_000L, result.missPico)
        assertEquals(212_000_000L, result.knownPico)
        assertTrue(result.incomplete); assertTrue(flagged(result, AiCostUnknown.CACHE_PRICE))
        val withoutTotal = usage("""{"usage":{"prompt_cache_miss_tokens":200,"completion_tokens":3}}""")
        assertFalse(withoutTotal.inputReported); assertFalse(withoutTotal.cacheHitReported)
        val incomplete = AiCostEstimate.calculate(withoutTotal, price)
        assertNull(incomplete.cachePico)
        assertEquals(212_000_000L, incomplete.knownPico)
        assertTrue(flagged(incomplete, AiCostUnknown.INPUT)); assertTrue(incomplete.incomplete)
    }

    @Test fun absentUsageAbsentPriceAndPartialInputOrOutputRemainUnknown() {
        val noUsage = AiCostEstimate.calculate(null, price)
        assertNull(noUsage.knownPico); assertTrue(flagged(noUsage, AiCostUnknown.USAGE))
        val noPrice = AiCostEstimate.calculate(AiTokenUsage(1, 2, 0, 3), null)
        assertNull(noPrice.knownPico); assertTrue(flagged(noPrice, AiCostUnknown.PRICE))
        val neither = AiCostEstimate.calculate(null, null)
        assertTrue(flagged(neither, AiCostUnknown.USAGE)); assertTrue(flagged(neither, AiCostUnknown.PRICE))
        val input = AiCostEstimate.calculate(usage("""{"usage":{"prompt_tokens":71}}"""), price)
        assertNull(input.outputPico); assertNull(input.knownPico)
        assertTrue(flagged(input, AiCostUnknown.OUTPUT)); assertTrue(flagged(input, AiCostUnknown.CACHE_PRICE))
        val output = AiCostEstimate.calculate(usage("""{"usage":{"completion_tokens":23}}"""), price)
        assertEquals(92_000_000L, output.knownPico); assertNull(output.cachePico); assertNull(output.missPico)
        assertTrue(flagged(output, AiCostUnknown.INPUT)); assertTrue(output.incomplete)
    }

    @Test fun unclassifiedInputCanBeExactlyPricedOnlyWhenBothInputRatesAreEqual() {
        val samePrice = AiPriceSnapshot.configured("1", "1", "4")
        val result = AiCostEstimate.calculate(usage("""{"usage":{"prompt_tokens":1000,"completion_tokens":1}}"""), samePrice)
        assertNull(result.cachePico); assertNull(result.missPico)
        assertEquals(1_000_000_000L, result.flatInputPico)
        assertEquals(1_004_000_000L, result.knownPico)
        assertFalse(result.incomplete)
        val reportedZero = AiCostEstimate.calculate(AiTokenUsage(), price)
        assertEquals(0L, reportedZero.knownPico); assertFalse(reportedZero.incomplete)
        assertNull(AiCostEstimate.calculate(null, price).knownPico)
    }

    @Test fun requestTicketFreezesPriceAndKeepsProviderModelAndEndpointIdentitiesSeparate() {
        val flash = AiProviderProfile.deepSeek()
        val pro = AiProviderProfile.deepSeek(AiConfig.DEEPSEEK_PRO_MODEL)
        val custom = AiProviderProfile.custom().copy(address = "https://synthetic-a.invalid/v1", model = flash.model)
        val otherCustom = custom.copy(address = "https://synthetic-b.invalid/v1")
        assertNotEquals(AiUsageTicket.providerKey(flash), AiUsageTicket.providerKey(pro))
        assertNotEquals(AiUsageTicket.providerKey(flash), AiUsageTicket.providerKey(custom))
        assertNotEquals(AiUsageTicket.providerKey(custom), AiUsageTicket.providerKey(otherCustom))
        assertNotEquals(AiUsageTicket.providerGroup(custom), AiUsageTicket.providerGroup(otherCustom))
        assertEquals("deepseek", AiUsageTicket.providerGroup(flash))
        val ticket = AiUsageTicket.start(flash, AiUsagePurpose.LIU_REN, price, 1_700_000_000_000)
        val changed = AiPriceSnapshot.configured("2", "3", "5")
        val tokens = AiTokenUsage(100, 0, 0, 0)
        assertEquals(2_000_000L, AiCostEstimate.calculate(tokens, ticket.price).knownPico)
        assertEquals(200_000_000L, AiCostEstimate.calculate(tokens, changed).knownPico)
        assertEquals(price.version, requireNotNull(ticket.price).version)
        assertEquals(AiUsagePurpose.LIU_REN, ticket.purpose)
    }

    @Test fun multiplicationAndSubtotalOverflowProduceUnknownInsteadOfNegativeOrInventedZero() {
        val huge = AiTokenUsage(cacheHit = Long.MAX_VALUE, inputReported = true, outputReported = false,
            cacheHitReported = true, cacheMissReported = false)
        val multiplied = AiCostEstimate.calculate(huge, price)
        assertNull(multiplied.cachePico); assertNull(multiplied.knownPico)
        assertTrue(flagged(multiplied, AiCostUnknown.RANGE)); assertTrue(multiplied.incomplete)
        val tinyPrice = AiPriceSnapshot("sum-overflow", 1, 1, 1)
        val summed = AiCostEstimate.calculate(AiTokenUsage(Long.MAX_VALUE - 2, 2, 0, 1), tinyPrice)
        assertEquals(Long.MAX_VALUE - 2, summed.cachePico)
        assertNull(summed.knownPico)
        assertTrue(flagged(summed, AiCostUnknown.RANGE)); assertTrue(summed.incomplete)
    }

    @Test fun priceValidationPreservesSixDecimalPrecisionAndRejectsInvalidOrOversizedValues() {
        val parsed = AiPriceSnapshot.configured(" 0.0200000 ", "1.000001", "1000000")
        assertEquals(20_000L, parsed.cacheRateMicros)
        assertEquals(1_000_001L, parsed.missRateMicros)
        assertEquals(1_000_000_000_000L, parsed.outputRateMicros)
        listOf("", "NaN", "Infinity", "-0.01", "0.0000001", "1000001", "1,2").forEach { bad ->
            assertTrue(bad, runCatching { AiPriceSnapshot.configured(bad, "1", "4") }.isFailure)
        }
        assertTrue(runCatching { AiPriceSnapshot("invalid", -1, 1, 1) }.isFailure)
    }
}
