package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class XiaoLiuRenAnalysisRepositoryTest {
    private val owners = ArrayList<CompletableJob>()
    private fun store() = XiaoLiuRenStore(RuntimeEnvironment.getApplication().getSharedPreferences(
        "liuren-analysis-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    private fun engine(store: XiaoLiuRenStore, create: suspend () -> DeepSeekClient): XiaoLiuRenAnalysisRepository {
        val owner = SupervisorJob().also { owners += it }
        return XiaoLiuRenAnalysisRepository(store, CoroutineScope(owner + Dispatchers.Default), create)
    }
    private val cast get() = LiuRenCast("明天面试，怎样准备？", LiuRenMode.NUMBERS,
        Instant.parse("2024-02-10T15:30:00Z").toEpochMilli(), "Asia/Shanghai", 1, 1, 1, digits = "012")
    private suspend fun settled(repo: XiaoLiuRenAnalysisRepository, cast: LiuRenCast = this.cast) =
        withTimeout(5000) { repo.state(cast).first { !it.loading } }
    @After fun close() = runBlocking { withTimeout(5000) { owners.forEach { it.cancelAndJoin() } } }

    @Test fun requestUsesOnlyThisQuestionAndComputedPalacesAndIgnoresModelActions() = runBlocking {
        val saved = store()
        val wire = FakeHttp(content = """{"reply":"先把面试中想讲的两件事准备好。","bills":[{"action":"delete","target_id":42}],"navigate":"settings","memory_updates":[{"kind":"fact","value":"untrusted","evidence":"model"}]}""")
        val repo = engine(saved) { wire.client() }
        repo.request(cast)
        val answer = settled(repo)
        assertTrue(answer.remote)
        assertEquals("先把面试中想讲的两件事准备好。", answer.reply)
        val request = wire.requests.single()
        assertEquals("fixture-liuren", request["model"]!!.jsonPrimitive.content)
        assertEquals(2, request["messages"]!!.jsonArray.size)
        val user = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        val data = Json.parseToJsonElement(user.substringBefore(DeepSeekClient.OUTPUT_CONTRACT)).jsonObject
        assertEquals(cast.question, data["question"]!!.jsonPrimitive.content)
        assertEquals(listOf(10, 1, 2), data["counts"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf("赤口", "赤口", "小吉"), data["palaces"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("012", data["reported_digits"]!!.jsonPrimitive.content)
        assertTrue(data["start_is_one"]!!.jsonPrimitive.boolean)
        assertFalse(request.toString().contains("ledger_context"))
        assertFalse(request.toString().contains("history"))
        assertEquals(listOf(AiUsagePurpose.LIU_REN), wire.purposes.toList())
        assertEquals(1, wire.finishes.get())
    }

    @Test fun duplicateRequestsAreCoalescedAndSavedAnalysisIsFreeOnReopen() = runBlocking {
        val saved = store()
        val wire = FakeHttp()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var factories = 0
        val repo = engine(saved) { factories++; entered.complete(Unit); release.await(); wire.client() }
        repo.request(cast); entered.await()
        repeat(4) { repo.request(cast) }
        assertEquals(1, factories); assertTrue(repo.state(cast).value.loading)
        release.complete(Unit)
        val answer = settled(repo)
        repo.request(cast)
        assertEquals(answer, repo.state(cast).value)
        assertEquals(1, wire.requests.size)
        val reopened = engine(saved) { error("cached or merely displayed results must not call the provider") }
        assertEquals(answer, reopened.state(cast).value)
        reopened.request(cast)
        assertEquals(answer, reopened.state(cast).value)
        assertEquals(1, wire.requests.size)
    }

    @Test fun failureKeepsQuestionAndLocalExplanationAndRetriesOnlyWhenRequested() = runBlocking {
        val saved = store()
        saved.saveSession(LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast))
        val wire = FakeHttp(status = 401)
        val repo = engine(saved) { wire.client() }
        repo.request(cast)
        val failed = settled(repo)
        assertFalse(failed.remote); assertNotNull(failed.error)
        assertTrue(failed.reply.contains("面试"))
        assertEquals(cast, saved.session().cast)
        assertEquals(1, wire.requests.size) // Authentication failure is never automatically retried.
        val reopened = engine(saved) { wire.client() }
        assertFalse(reopened.state(cast).value.remote)
        assertEquals(1, wire.requests.size) // Restoring failed results does not spend more tokens.
        wire.status = 200
        reopened.request(cast)
        assertTrue(settled(reopened).remote)
        assertEquals(2, wire.requests.size)
        assertEquals(cast, saved.session().cast)
    }

    @Test fun replacingQuestionCancelsItsWorkWithoutLeavingAnUnretryableLoadingState() = runBlocking {
        val saved = store(); val wire = FakeHttp()
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>(); var first = true
        val repo = engine(saved) {
            if (first) {
                first = false; entered.complete(Unit)
                try { never.await() } finally { cancelled.complete(Unit) }
            }
            wire.client()
        }
        repo.request(cast); entered.await()
        val second = cast.copy(question = "出门之前准备什么？", capturedAtMillis = cast.capturedAtMillis + 1)
        repo.request(second)
        cancelled.await()
        assertFalse(repo.state(cast).value.loading)
        assertNotNull(repo.state(cast).value.error)
        assertTrue(settled(repo, second).remote)
        assertEquals(1, wire.requests.size)
        repo.request(cast)
        assertTrue(settled(repo).remote)
        assertEquals(2, wire.requests.size)
        assertTrue(repo.state(second).value.remote)
    }

    @Test fun closingTheCallerDoesNotCancelAppOwnedAnalysisAndSecondsCannotCreateAnotherFee() = runBlocking {
        val saved = store(); val wire = FakeHttp()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = engine(saved) { entered.complete(Unit); release.await(); wire.client() }
        val pageOwner = SupervisorJob()
        CoroutineScope(pageOwner + Dispatchers.Default).async { repo.request(cast) }.await()
        entered.await(); pageOwner.cancelAndJoin()
        assertTrue(repo.state(cast).value.loading)
        release.complete(Unit)
        assertTrue(settled(repo).remote)
        assertEquals(1, wire.requests.size)
        assertNotEquals(XiaoLiuRenAnalysisRepository.key(cast), XiaoLiuRenAnalysisRepository.key(cast.copy(question = "另一个问题")))
        val sameNumbers = cast.copy(capturedAtMillis = cast.capturedAtMillis + 60_000)
        assertEquals(XiaoLiuRenAnalysisRepository.key(cast), XiaoLiuRenAnalysisRepository.key(sameNumbers))
        repo.request(sameNumbers)
        assertEquals(1, wire.requests.size)
        val time = cast.copy(mode = LiuRenMode.TIME, digits = "")
        assertEquals(XiaoLiuRenAnalysisRepository.key(time), XiaoLiuRenAnalysisRepository.key(time.copy(capturedAtMillis = time.capturedAtMillis + 1)))
        assertEquals(XiaoLiuRenAnalysisRepository.key(time), XiaoLiuRenAnalysisRepository.key(time.copy(question = "  明天面试，怎样准备？ \n")))
        assertNotEquals(XiaoLiuRenAnalysisRepository.key(time), XiaoLiuRenAnalysisRepository.key(time.copy(shichen = 2)))
        assertNotEquals(XiaoLiuRenAnalysisRepository.key(time), XiaoLiuRenAnalysisRepository.key(time.copy(capturedAtMillis = time.capturedAtMillis + 86_400_000)))
        assertNotEquals(XiaoLiuRenAnalysisRepository.key(cast), XiaoLiuRenAnalysisRepository.key(cast.copy(digits = "013")))
    }

    private class FakeHttp(@Volatile var status: Int = 200, val content: String = """{"bills":[],"reply":"先留一小步给自己，阿噜陪你准备。"}""") {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val purposes = CopyOnWriteArrayList<AiUsagePurpose>()
        val finishes = AtomicInteger()
        fun client(): DeepSeekClient {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                // The interceptor is terminal: no socket, DNS lookup, localhost server or real API.
                val body = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
                    put("usage", buildJsonObject { put("prompt_tokens", 100); put("completion_tokens", 20) })
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            val profile = AiProviderProfile.custom().copy(address = "https://liuren-fixture.invalid/v1", model = "fixture-liuren")
            return DeepSeekClient("synthetic-only", profile, http).forPurpose(AiUsagePurpose.LIU_REN).meteredBy(object : AiUsageMeter {
                override suspend fun begin(profile: AiProviderProfile, purpose: AiUsagePurpose): AiUsageTicket {
                    purposes += purpose
                    return AiUsageTicket.start(profile, purpose, null, 1_700_000_000_000)
                }
                override suspend fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?) { finishes.incrementAndGet() }
            })
        }
    }
}
