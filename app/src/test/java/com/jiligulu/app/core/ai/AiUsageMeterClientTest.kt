package com.jiligulu.app.core.ai

import android.app.Application
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AiUsageMeterClientTest {
    private val profile = AiProviderProfile.custom().copy(name = "Meter fixture", address = "https://meter.invalid/v1", model = "fixture-v1")
    private class Meter : AiUsageMeter {
        var price = AiPriceSnapshot.userDeepSeekDefault
        val starts = Collections.synchronizedList(mutableListOf<AiUsageTicket>())
        val finishes = Collections.synchronizedList(mutableListOf<Pair<AiUsageTicket, AiTokenUsage?>>())
        override suspend fun begin(profile: AiProviderProfile, purpose: AiUsagePurpose) = AiUsageTicket.start(profile, purpose, price, 1000).also { starts += it }
        override suspend fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?) { finishes += ticket to usage }
    }
    private fun client(meter: Meter, response: (Int) -> Pair<Int, String>): Pair<DeepSeekClient, () -> Int> {
        var attempts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = response(++attempts)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return DeepSeekClient("synthetic-meter-key", profile, http).meteredBy(meter) to { attempts }
    }

    @Test fun malformedReplyRetriesAreDifferentRealAttemptsAndNeverRecordDuringJsonParsing() = runBlocking {
        val meter = Meter()
        val (client, attempts) = client(meter) { attempt -> 200 to reply(if (attempt == 1) "invalid json" else """{"reply":"ok","bills":[]}""", usage()) }
        assertEquals("ok", client.forPurpose(AiUsagePurpose.LEDGER_CHAT).parseBill("system", "input").getOrThrow().reply)
        assertEquals(2, attempts())
        assertEquals(2, meter.starts.size)
        assertEquals(2, meter.finishes.size)
        assertEquals(2, meter.starts.map { it.id }.toSet().size)
        assertTrue(meter.finishes.all { it.second!!.output == 80L && it.first.purpose == AiUsagePurpose.LEDGER_CHAT })
    }

    @Test fun absentAndPartialUsageDoNotInventCountersAndTheChosenPurposeIsObservedExactlyOnce() = runBlocking {
        for (reported in listOf<JsonElement?>(null, JsonNull, buildJsonObject { put("completion_tokens", 23) })) {
            val meter = Meter()
            val (client, attempts) = client(meter) { 200 to reply("""{"reply":"ok","bills":[]}""", reported) }
            assertTrue(client.parseBill("system", "input", purpose = AiUsagePurpose.CLASSIFICATION).isSuccess)
            assertEquals(1, attempts())
            assertEquals(1, meter.finishes.size)
            assertEquals(AiUsagePurpose.CLASSIFICATION, meter.finishes.single().first.purpose)
            if (reported == null || reported == JsonNull) assertNull(meter.finishes.single().second)
            else {
                val usage = meter.finishes.single().second!!
                assertFalse(usage.inputReported); assertFalse(usage.cacheHitReported); assertFalse(usage.cacheMissReported)
                assertEquals(23L, usage.output)
            }
        }
    }

    @Test fun eachAttemptKeepsThePriceBeforeTheTransportChangesTheConfiguration() = runBlocking {
        val meter = Meter()
        val old = meter.price
        val (client, _) = client(meter) {
            meter.price = AiPriceSnapshot.configured("0.5", "2", "10")
            200 to reply("""{"reply":"ok","bills":[]}""", usage())
        }
        assertTrue(client.forPurpose(AiUsagePurpose.HEART_LETTER).parseBill("system", "input").isSuccess)
        assertEquals(old, meter.finishes.single().first.price)
        assertEquals(AiUsagePurpose.HEART_LETTER, meter.finishes.single().first.purpose)
    }

    @Test fun httpErrorUsageStillCountsAndObserverFailureCannotCauseAnExtraPaidRequest() = runBlocking {
        val meter = Meter()
        val (client, attempts) = client(meter) { 401 to reply("error", usage()) }
        assertTrue(client.parseBill("system", "input").isFailure)
        assertEquals(1, attempts())
        assertEquals(80L, meter.finishes.single().second!!.output)
        val failing = object : AiUsageMeter {
            override suspend fun begin(profile: AiProviderProfile, purpose: AiUsagePurpose) = AiUsageTicket.start(profile, purpose, null, 1000)
            override suspend fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?) { throw IOException("Synthetic local persistence failure") }
        }
        val (success, successAttempts) = client(Meter()) { 200 to reply("""{"reply":"ok","bills":[]}""", usage()) }
        assertTrue(success.meteredBy(failing).parseBill("system", "input").isSuccess)
        assertEquals(1, successAttempts())
    }

    @Test fun unsupportedImageHasNoAttemptWhileSupportedImagesCarryTheImagePurpose() = runBlocking {
        val meter = Meter()
        val (base, attempts) = client(meter) { 200 to reply("{}", usage()) }
        assertTrue(base.recognizeImage("system", "context", "ZmFrZQ==").isFailure)
        assertEquals(0, attempts()); assertTrue(meter.starts.isEmpty())
        assertTrue(base.configuredFor(profile.copy(supportsImages = true)).recognizeImage("system", "context", "ZmFrZQ==").isSuccess)
        assertEquals(1, attempts())
        assertEquals(AiUsagePurpose.IMAGE_RECOGNITION, meter.finishes.single().first.purpose)
    }

    private fun usage() = buildJsonObject { put("prompt_tokens", 1000); put("prompt_cache_hit_tokens", 900); put("prompt_cache_miss_tokens", 100); put("completion_tokens", 80) }
    private fun reply(content: String, usage: JsonElement?) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
        if (usage != null) put("usage", usage)
    }.toString()
}
