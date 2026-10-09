package com.jiligulu.app.ui.settings

import com.jiligulu.app.core.ai.AiUsagePurpose
import com.jiligulu.app.data.prefs.AiCostGroup
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class AiCostSummaryTest {
    @Test fun cacheRateUsesReportedTokensAndPriceUnknownDoesNotMakeUsageUnknown() {
        val summary = summarizeAiCost(listOf(costGroup(hit = 900, miss = 100, known = null, unknown = 1)))
        assertEquals("90%", summary.cacheText)
        assertEquals("完整报告", summary.cacheNote)
        assertNull(summary.knownPico)
        assertNull(summary.displayPico)
        assertEquals(1L, summary.unknownCalls)
        assertTrue(summary.tokensComplete)
    }

    @Test fun missingCacheSplitIsNeverPresentedAsZeroPercentOrAsComplete() {
        val missing = costGroup(hit = 0, miss = 0, known = 60, unknown = 1)
            .copy(unclassifiedInput = 1_000, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0, cacheReportedCalls = 0)
        val summary = summarizeAiCost(listOf(missing))
        assertNull(summary.cacheRate)
        assertEquals("—", summary.cacheText)
        assertEquals("未报告缓存", summary.cacheNote)
        assertEquals(BigDecimal.valueOf(1_000), summary.inputTokens)
        assertEquals(60L, summary.knownPico)
    }

    @Test fun aReportedSubsetKeepsItsRateButLabelsTheMissingCoverage() {
        val incomplete = costGroup(hit = 0, miss = 0, known = null, unknown = 1)
            .copy(cacheHitReportedCalls = 0, cacheMissReportedCalls = 0, inputReportedCalls = 0, outputReportedCalls = 0)
        val summary = summarizeAiCost(listOf(costGroup(hit = 900, miss = 100), incomplete))
        assertEquals("90%", summary.cacheText)
        assertEquals("仅已报告部分", summary.cacheNote)
        assertFalse(summary.cacheComplete)
        assertFalse(summary.tokensComplete)
        assertEquals(1L, summary.unknownCalls)
    }

    @Test fun oneSidedCacheReportCannotClaimACompleteHundredPercentRate() {
        val summary = summarizeAiCost(listOf(costGroup(hit = 1_000, miss = 0)
            .copy(cacheMissReportedCalls = 0)))
        assertEquals("—", summary.cacheText)
        assertEquals("报告不完整", summary.cacheNote)
        assertFalse(summary.cacheComplete)
    }

    @Test fun reportedZeroInputHasNoDenominatorAndDoesNotInventARate() {
        val summary = summarizeAiCost(listOf(costGroup(hit = 0, miss = 0)))
        assertNull(summary.cacheRate)
        assertEquals("无输入 tokens", summary.cacheNote)
        assertTrue(summary.cacheComplete)
    }

    @Test fun oneSidedReportsDoNotPolluteTheRateOfCompleteReportedRequests() {
        val partial = costGroup(hit = 5_000, miss = 0).copy(cacheMissReportedCalls = 0)
        val summary = summarizeAiCost(listOf(costGroup(hit = 900, miss = 100), partial))
        assertEquals("90%", summary.cacheText)
        assertEquals("仅已报告部分", summary.cacheNote)
        assertEquals(BigDecimal.valueOf(6_000), summary.inputTokens)
    }

    @Test fun separateOneSidedCallsInOneGroupCannotInventAPairedRatio() {
        val mixed = costGroup(hit = 900, miss = 100).copy(calls = 2, cacheReportedCalls = 2,
            cacheHitReportedCalls = 1, cacheMissReportedCalls = 1)
        val summary = summarizeAiCost(listOf(mixed))
        assertEquals("—", summary.cacheText)
        assertEquals("报告不完整", summary.cacheNote)
    }

    @Test fun oldCountersWithoutARecoveredEstimateRemainUnpricedEvenWhenTheyHaveReportedTokens() {
        val legacy = costGroup(hit = 40, miss = 60, known = null, unknown = 1)
            .copy(legacyCalls = 1, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0, cacheReportedCalls = 0)
        val summary = summarizeAiCost(listOf(legacy))
        assertNull(summary.knownPico)
        assertEquals("未知", money(summary.knownPico))
        assertEquals("未知", money(summary.displayPico))
        assertEquals(1L, summary.legacyCalls)
        assertEquals("40%", summary.cacheText)
        assertEquals("仅已报告部分", summary.cacheNote)
    }

    @Test fun emptyHistoryAndUnknownHistoryHaveDifferentMoneyAndCacheStates() {
        val empty = summarizeAiCost(emptyList())
        val unknown = summarizeAiCost(listOf(costGroup(hit = 0, miss = 0, known = null, unknown = 1)
            .copy(cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)))
        assertEquals("¥0", money(empty.knownPico))
        assertEquals("¥0", money(empty.displayPico))
        assertEquals("暂无请求", empty.cacheNote)
        assertEquals("未知", money(unknown.knownPico))
        assertEquals("未知", money(unknown.displayPico))
        assertEquals("未报告缓存", unknown.cacheNote)
    }

    @Test fun tokenRateAndTotalsDoNotOverflowWhenSeveralLongCountersAreCombined() {
        val summary = summarizeAiCost(listOf(costGroup(hit = Long.MAX_VALUE, miss = Long.MAX_VALUE), costGroup(hit = Long.MAX_VALUE, miss = Long.MAX_VALUE)))
        assertEquals("50%", summary.cacheText)
        assertEquals(BigDecimal.valueOf(Long.MAX_VALUE).multiply(BigDecimal.valueOf(4)), summary.inputTokens)
    }

    @Test fun subMicroYuanKnownAmountsStayVisibleAndAnOverflowNeverBecomesZero() {
        assertEquals("<¥0.000001", money(12L))
        val summary = summarizeAiCost(listOf(costGroup(known = Long.MAX_VALUE), costGroup(known = 1)))
        assertNull(summary.knownPico)
        assertEquals("未知", money(summary.knownPico))
        assertNull(summary.displayPico)
        assertEquals("未知", money(summary.displayPico))
    }

    @Test fun recoveredLegacyReferenceIsDisplayedSeparatelyFromKnownSnapshotPricing() {
        val legacy = costGroup(hit = 40, miss = 60, known = null, unknown = 1)
            .copy(legacyCalls = 1, legacyEstimatePico = 2_000_000_000,
                cacheHitReportedCalls = 0, cacheMissReportedCalls = 0, cacheReportedCalls = 0)
        val legacySummary = summarizeAiCost(listOf(legacy))
        assertNull(legacySummary.knownPico)
        assertEquals(2_000_000_000L, legacySummary.displayPico)
        assertEquals("¥0.002", money(legacySummary.displayPico))
        assertEquals(1L, legacySummary.unknownCalls)
        assertEquals(1L, legacySummary.legacyCalls)
        assertEquals("40%", legacySummary.cacheText)
        assertEquals("仅已报告部分", legacySummary.cacheNote)

        val combined = summarizeAiCost(listOf(costGroup(), legacy))
        assertEquals(1_000_000_000L, combined.knownPico)
        assertEquals(3_000_000_000L, combined.displayPico)
        assertEquals(1L, combined.unknownCalls)
    }

    @Test fun undatedReferenceContributesToAccumulatedCostWithoutBecomingADailyAmount() {
        val dated = costGroup()
        val undated = costGroup(known = null, unknown = 1).copy(day = null,
            legacyCalls = 1, legacyEstimatePico = 2_000_000_000)
        val total = summarizeAiCost(listOf(dated, undated))
        val selectedDay = summarizeAiCost(listOf(dated, undated).filter { it.day == "2026-10-09" })
        assertEquals(3_000_000_000L, total.displayPico)
        assertEquals(1_000_000_000L, selectedDay.displayPico)
        assertEquals(1L, selectedDay.calls)
        assertEquals(0L, selectedDay.legacyCalls)
    }

    @Test fun legacyReferenceOverflowDoesNotEraseKnownPricingOrBecomeZero() {
        val legacy = costGroup(known = null, unknown = 1).copy(legacyCalls = 1, legacyEstimatePico = Long.MAX_VALUE)
        val summary = summarizeAiCost(listOf(costGroup(known = 1), legacy))
        assertEquals(1L, summary.knownPico)
        assertNull(summary.displayPico)
        assertEquals("未知", money(summary.displayPico))
    }

    @Test fun oldCacheCountersWithoutIndividualCoverageFlagsRetainTheirReportedRatio() {
        val legacy = costGroup(hit = 40, miss = 60, known = null, unknown = 1).copy(
            legacyCalls = 1, cacheReportedCalls = 1, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)
        val summary = summarizeAiCost(listOf(legacy))
        assertEquals("40%", summary.cacheText)
        assertEquals("仅已报告部分", summary.cacheNote)
    }
}

internal fun costGroup(hit: Long = 900, miss: Long = 100, known: Long? = 1_000_000_000, unknown: Long = 0) = AiCostGroup(
    day = "2026-10-09", purpose = AiUsagePurpose.LEDGER_CHAT, providerKey = "fixture", providerName = "Fixture", model = "fixture-v1",
    calls = 1, reportedCalls = 1, cacheHit = hit, cacheMiss = miss, unclassifiedInput = 0, output = 60,
    cachePico = null, missPico = null, outputPico = null, flatPico = null, knownPico = known,
    unknownCalls = unknown, legacyCalls = 0, inputReportedCalls = 1, outputReportedCalls = 1,
    cacheReportedCalls = 1, cacheHitReportedCalls = 1, cacheMissReportedCalls = 1
)
