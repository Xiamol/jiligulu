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
        val fixture = validReading(cast.question).toMutableMap().apply {
            put("bills", Json.parseToJsonElement("""[{"action":"delete","target_id":42}]"""))
            put("navigate", JsonPrimitive("settings"))
            put("memory_updates", Json.parseToJsonElement("""[{"kind":"fact","value":"untrusted","evidence":"model"}]"""))
        }
        val wire = FakeHttp(content = JsonObject(fixture).toString())
        val repo = engine(saved) { wire.client() }
        repo.request(cast)
        val answer = settled(repo)
        assertTrue(answer.remote)
        val reading = requireNotNull(answer.reading)
        assertEquals(cast.question, reading.question)
        assertEquals(listOf("赤口", "赤口", "小吉"), reading.stages.map { it.palace })
        assertEquals(listOf("赤口" to "赤口", "赤口" to "小吉"), reading.links.map { it.from to it.to })
        assertEquals(listOf(LiuRenRelation.SAME, LiuRenRelation.GENERATES), reading.links.map { it.relation })
        assertEquals(fixture["summary"]!!.jsonPrimitive.content, reading.summary)
        assertEquals(fixture["advice"]!!.jsonPrimitive.content, reading.advice)
        fixture["stages"]!!.jsonArray.forEach { assertTrue(answer.reply.contains(it.jsonObject["text"]!!.jsonPrimitive.content)) }
        fixture["links"]!!.jsonArray.forEach { assertTrue(answer.reply.contains(it.jsonObject["text"]!!.jsonPrimitive.content)) }
        assertTrue(answer.reply.contains("起点 · 赤口")); assertTrue(answer.reply.contains("过程 · 赤口")); assertTrue(answer.reply.contains("趋向 · 小吉"))
        val request = wire.requests.single()
        assertEquals("fixture-liuren", request["model"]!!.jsonPrimitive.content)
        assertEquals(2, request["messages"]!!.jsonArray.size)
        val user = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        val data = parseInput(request)
        assertFalse(user.contains(DeepSeekClient.OUTPUT_CONTRACT))
        assertTrue(user.endsWith("\n请只返回 system 中指定的 JSON 对象。"))
        assertEquals(cast.question, data["question"]!!.jsonPrimitive.content)
        assertEquals(listOf(10, 1, 2), data["counts"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf("赤口", "赤口", "小吉"), data["palaces"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("012", data["reported_digits"]!!.jsonPrimitive.content)
        assertTrue(data["start_is_one"]!!.jsonPrimitive.boolean)
        assertEquals("six-palace-question-chain-v4", data["interpretation_version"]!!.jsonPrimitive.content)
        assertEquals(listOf("起点", "过程", "趋向"), data["stages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("赤口", "赤口", "小吉"), data["stages"]!!.jsonArray.map { it.jsonObject["palace"]!!.jsonPrimitive.content })
        assertEquals(listOf("金", "金", "水"), data["stages"]!!.jsonArray.map { it.jsonObject["element"]!!.jsonPrimitive.content })
        assertEquals(listOf("SAME", "GENERATES"), data["link_rules"]!!.jsonArray.map { it.jsonObject["relation"]!!.jsonPrimitive.content })
        assertFalse(request.toString().contains("ledger_context"))
        assertFalse(request.toString().contains("history"))
        assertEquals(listOf(AiUsagePurpose.LIU_REN), wire.purposes.toList())
        assertEquals(1, wire.finishes.get())
        assertNotNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
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

    @Test fun unqualifiedReadingsUseOneAttemptAndOnlyManualRetryCanReplaceTheFallback() = runBlocking {
        val onlyTerminal = """{"reply":"末宫小吉，先留一小步给自己，阿噜陪你准备。","bills":[]}"""
        val wrongPalace = validReading(cast.question).toMutableMap().apply {
            put("stages", buildJsonArray {
                add(buildJsonObject { put("palace", "大安"); put("text", "面试准备要先梳理自己的材料，再把已有经历整理成简短而具体的表达。") })
                validReading(cast.question)["stages"]!!.jsonArray.drop(1).forEach { add(it) }
            })
        }.let { JsonObject(it).toString() }
        val badShape = """{"question":"明天面试，怎样准备？","summary":"内容缺少三段与两段转折","stages":"小吉","links":[],"advice":"先准备"}"""
        val wrongRelation = validReading(cast.question).toMutableMap().apply {
            put("links", buildJsonArray {
                add(validReading(cast.question)["links"]!!.jsonArray.first())
                add(JsonObject(validReading(cast.question)["links"]!!.jsonArray.last().jsonObject.toMutableMap().apply {
                    put("relation", JsonPrimitive("CONTROLS"))
                }))
            })
        }.let { JsonObject(it).toString() }
        val genericStages = validReading(cast.question).toMutableMap().apply {
            put("stages", buildJsonArray {
                listOf("赤口", "赤口", "小吉").forEach { palace -> add(buildJsonObject {
                    put("palace", palace); put("text", "先照顾自己，慢慢调整心情，给生活留一点耐心，平稳地迈出一小步就好了。")
                }) }
            })
        }.let { JsonObject(it).toString() }
        val guaranteed = validReading(cast.question).toMutableMap().apply {
            put("advice", JsonPrimitive("准备面试材料后肯定会被录用，保证没有任何困难，所以不用再确认后续通知方式。"))
        }.let { JsonObject(it).toString() }
        for (bad in listOf(onlyTerminal, wrongPalace, wrongRelation, genericStages, guaranteed, badShape, "{ malformed JSON", "")) {
            val saved = store()
            saved.saveSession(LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast))
            val wire = FakeHttp(content = bad)
            val repo = engine(saved) { wire.client() }
            repo.request(cast)
            val failed = settled(repo)
            assertFalse("Unqualified content must not replace the local reading", failed.remote)
            assertNotNull(failed.error)
            assertNotNull(failed.reading)
            assertTrue(failed.reply.contains("面试"))
            assertEquals(cast, saved.session().cast)
            assertNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
            assertEquals(1, wire.requests.size)
            assertEquals(listOf(AiUsagePurpose.LIU_REN), wire.purposes.toList())
            assertEquals(1, wire.finishes.get())
            repeat(3) { repo.state(cast) }
            val reopened = engine(saved) { wire.client() }
            assertFalse(reopened.state(cast).value.remote)
            assertEquals(1, wire.requests.size) // Merely reopening a failed answer does not buy a repair.
            wire.content = null // A later explicit retry now receives a qualified complete reading.
            reopened.request(cast)
            val retried = settled(reopened)
            assertTrue(retried.remote)
            assertEquals(3, requireNotNull(retried.reading).stages.size)
            assertEquals(2, retried.reading!!.links.size)
            assertNotNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
            assertEquals(2, wire.requests.size)
            assertEquals(listOf(AiUsagePurpose.LIU_REN, AiUsagePurpose.LIU_REN), wire.purposes.toList())
            assertEquals(2, wire.finishes.get())
        }
    }

    @Test fun legacySinglePalaceCacheIsRejectedInsteadOfBeingShownAsACachedCompleteReading() = runBlocking {
        val saved = store()
        saved.saveAnalysis(XiaoLiuRenAnalysisRepository.key(cast), "末宫小吉，今天慢慢来就好。")
        val wire = FakeHttp()
        val repo = engine(saved) { wire.client() }
        val local = repo.state(cast).value
        assertFalse(local.remote)
        assertEquals(3, requireNotNull(local.reading).stages.size)
        assertTrue(wire.requests.isEmpty())
        repo.request(cast)
        assertTrue(settled(repo).remote)
        assertEquals(1, wire.requests.size)
        assertEquals(1, wire.finishes.get())
    }

    private class FakeHttp(@Volatile var status: Int = 200, @Volatile var content: String? = null) {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val purposes = CopyOnWriteArrayList<AiUsagePurpose>()
        val finishes = AtomicInteger()
        fun client(): DeepSeekClient {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val packet = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                requests += packet
                val answer = content ?: validReading(parseInput(packet)["question"]!!.jsonPrimitive.content).toString()
                // The interceptor is terminal: no socket, DNS lookup, localhost server or real API.
                val body = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", answer) }) }) })
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

    companion object {
        private fun parseInput(request: JsonObject): JsonObject {
            val content = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            return Json.parseToJsonElement(content.substringBefore("\n请只返回 ")).jsonObject
        }
        /** Independent wire fixture, not the production local-reading generator. */
        private fun validReading(question: String) = buildJsonObject {
            val travel = question.contains("出门")
            put("question", question)
            put("summary", if (travel) "就这次出行，赤口在前两段重复，提醒把时间安排和路线沟通清楚；最后小吉更适合看成逐步落实行程，不能只凭末宫决定安排。"
                else "就明天这次面试，赤口在起点与过程重复，提醒先梳理表达和材料；最后小吉可以看成后续争取的小进展，不能只用末宫回答录用结果。")
            put("stages", buildJsonArray {
                add(buildJsonObject { put("palace", "赤口"); put("text", if (travel) "起点赤口提醒出行准备中的沟通细节，先把时间、路线和必要证件列清楚，减少临时解释。" else "起点赤口提醒面试准备中的表达细节，先把两段相关经历和材料整理清楚，避免一开始就说得含糊。") })
                add(buildJsonObject { put("palace", "赤口"); put("text", if (travel) "过程再见赤口，出行安排需要再次核对时间和路线，遇到变化先商量，不把重复确认理解成坏结果。" else "过程再见赤口，现场沟通与面试流程需要留意，回答问题时先听清要求，再用具体经历回应。") })
                add(buildJsonObject { put("palace", "小吉"); put("text", if (travel) "趋向小吉，出行可以争取把一项安排落实下来；仍需确认行程和路线，而不是直接假定一路没有变化。" else "趋向小吉，面试后可以争取一项明确的小进展，例如确认后续流程与通知方式，仍需等待真实录用消息。") })
            })
            put("links", buildJsonArray {
                add(buildJsonObject { put("from", "赤口"); put("to", "赤口"); put("relation", "SAME"); put("text", "赤口到赤口是同一沟通线索在不同阶段的延续；前段准备清楚，过程仍要根据实际反馈调整表达。") })
                add(buildJsonObject { put("from", "赤口"); put("to", "小吉"); put("relation", "GENERATES"); put("text", "赤口到小吉可以把前段的沟通调整看成后续小进展的条件；处理分歧比只期待末宫的顺意更实际。") })
            })
            put("advice", if (travel) "核对出行时间、票证和路线，再准备一条可执行的备选安排，之后根据实际天气和反馈调整。"
                else "核对面试时间与材料，准备两段能说明能力的经历，再练习简短而清楚的回答，并问清后续通知方式。")
        }
    }
}
