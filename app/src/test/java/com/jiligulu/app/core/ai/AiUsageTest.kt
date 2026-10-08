package com.jiligulu.app.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AiUsageTest {
    private fun parse(raw: String) = AiTokenUsage.fromResponse(Json.parseToJsonElement(raw).jsonObject)

    @Test fun providerCountsAreUsedWithoutCountingReasoningTwice() {
        assertEquals(AiTokenUsage(900, 100, 0, 80), parse("""{"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":100,"completion_tokens":80,"completion_tokens_details":{"reasoning_tokens":50}}}"""))
    }

    @Test fun unknownCacheMetadataIsNotPretendedToBeAMissRate() {
        val usage = parse("""{"usage":{"prompt_tokens":1000,"completion_tokens":100}}""")!!
        assertEquals(1000L, usage.unclassifiedInput)
        val totals = AiUsageTotals().add(usage)
        assertNull(totals.hitRate)
        assertEquals(.0014, totals.estimatedYuan, 0.00000001)
    }

    @Test fun compatibleCachedTokenMetadataCanInferMisses() {
        assertEquals(AiTokenUsage(600, 400, 0, 90), parse("""{"usage":{"prompt_tokens":1000,"prompt_tokens_details":{"cached_tokens":600},"completion_tokens":90}}"""))
    }

    @Test fun missingOrMalformedUsageDoesNotInventBilledTokens() {
        assertNull(parse("""{"error":"network"}"""))
        assertNull(parse("""{"usage":{"prompt_tokens":-1,"completion_tokens":"oops"}}"""))
        assertNull(parse("""{"usage":null}"""))
        assertEquals(0L, AiUsageTotals().add(null).reportedCalls)
        assertEquals(1L, AiUsageTotals().add(null).calls)
    }

    @Test fun partialUsageDoesNotClaimMissingInputOutputOrCacheWasReported() {
        val outputOnly = parse("""{"usage":{"completion_tokens":23}}""")!!
        assertFalse(outputOnly.inputReported)
        assertTrue(outputOnly.outputReported)
        assertFalse(outputOnly.cacheReported)
        assertEquals(0L, outputOnly.unclassifiedInput)
        assertEquals(23L, outputOnly.output)

        val inputOnly = parse("""{"usage":{"prompt_tokens":71}}""")!!
        assertTrue(inputOnly.inputReported)
        assertFalse(inputOnly.outputReported)
        assertFalse(inputOnly.cacheReported)
        assertEquals(71L, inputOnly.unclassifiedInput)
        assertEquals(0L, inputOnly.cacheMiss)
    }

    @Test fun mixedPartialUsageTracksCoverageSeparatelyFromTokenCounters() {
        val totals = AiUsageTotals()
            .add(parse("""{"usage":{"completion_tokens":23}}"""))
            .add(parse("""{"usage":{"prompt_tokens":71}}"""))
            .add(parse("""{"usage":{"prompt_cache_hit_tokens":30,"prompt_cache_miss_tokens":10}}"""))
            .add(null)
        assertEquals(4L, totals.calls)
        assertEquals(3L, totals.reportedCalls)
        assertEquals(2L, totals.inputReportedCalls)
        assertEquals(1L, totals.outputReportedCalls)
        assertEquals(1L, totals.cacheReportedCalls)
        assertEquals(30L, totals.cacheHit)
        assertEquals(10L, totals.cacheMiss)
        assertEquals(71L, totals.unclassifiedInput)
        assertEquals(23L, totals.output)
        assertEquals(.75, totals.hitRate!!, .000001)
    }

    @Test fun inconsistentProviderCountersAreBoundedByPromptTotal() {
        assertEquals(AiTokenUsage(80, 20, 0, 1), parse("""{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":100,"completion_tokens":1}}"""))
    }

    @Test fun hitRateIsTokenWeightedAndEveryRetryAddsItsUsage() {
        val totals = AiUsageTotals().add(AiTokenUsage(90, 10, 0, 20)).add(AiTokenUsage(0, 900, 0, 30)).add(null)
        assertEquals(.09, totals.hitRate!!, 0.00001)
        assertEquals(3L, totals.calls)
        assertEquals(2L, totals.reportedCalls)
        assertEquals((90 * .02 + 910 + 50 * 4) / 1_000_000, totals.estimatedYuan, .000000001)
    }

    @Test fun newDayResetsDailyTotalsButKeepsLifetimeAndStartDate() {
        val first = AiUsageSnapshot().add("2026-10-02", AiTokenUsage(100, 20, 0, 10))
        assertEquals(AiUsageTotals(), first.forDay("2026-10-03"))
        val next = first.add("2026-10-03", AiTokenUsage(10, 5, 0, 3))
        assertEquals("2026-10-02", next.since)
        assertEquals(1L, next.today.calls)
        assertEquals(2L, next.total.calls)
        assertEquals(110L, next.total.cacheHit)
    }
}
