package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
            // Model-owned fields are untrusted: the computed course and original question stay local.
            put("question", JsonPrimitive("改问另一个问题"))
            put("palaces", Json.parseToJsonElement("""["大安","空亡","速喜"]"""))
            put("stages", Json.parseToJsonElement("""[{"palace":"大安","text":"不可信的模型改盘"}]"""))
            put("links", Json.parseToJsonElement("""[{"from":"大安","to":"空亡","relation":"SAME","text":"不可信的模型关系"}]"""))
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
        assertEquals(fixture["answer"]!!.jsonPrimitive.content, reading.summary)
        assertEquals(fixture["reason"]!!.jsonPrimitive.content, reading.reason)
        assertEquals(fixture["advice"]!!.jsonPrimitive.content, reading.advice)
        assertTrue(answer.reply.startsWith(fixture["answer"]!!.jsonPrimitive.content))
        assertTrue(answer.reply.contains(fixture["reason"]!!.jsonPrimitive.content))
        assertTrue(answer.reply.contains(fixture["advice"]!!.jsonPrimitive.content))
        assertFalse(answer.reply.contains("不可信"))
        assertFalse(answer.reply.contains("改问另一个问题"))
        assertTrue(reading.stages.all { it.text.isNotBlank() })
        assertTrue(reading.links.all { it.text.isNotBlank() })
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
        assertEquals("six-palace-answer-first-v5", data["interpretation_version"]!!.jsonPrimitive.content)
        assertEquals(listOf("起点", "过程", "趋向"), data["stages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("赤口", "赤口", "小吉"), data["stages"]!!.jsonArray.map { it.jsonObject["palace"]!!.jsonPrimitive.content })
        assertEquals(listOf("金", "金", "水"), data["stages"]!!.jsonArray.map { it.jsonObject["element"]!!.jsonPrimitive.content })
        assertEquals(listOf("SAME", "GENERATES"), data["link_rules"]!!.jsonArray.map { it.jsonObject["relation"]!!.jsonPrimitive.content })
        assertFalse(request.toString().contains("ledger_context"))
        assertFalse(request.toString().contains("history"))
        assertNotNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
    }

    @Test fun conciseAnswerIsAcceptedWithoutEchoingQuestionOrManufacturingStageObjects() = runBlocking {
        val saved = store()
        val concise = buildJsonObject {
            put("answer", "有机会，但最终录用仍需真实确认。")
            put("reason", "前两段提醒把沟通做清楚，最后是小进展；收到正面反馈和正式结果需要分开看。")
            put("advice", "先整理两段经历，再问清通知方式。")
        }
        val wire = FakeHttp(content = concise.toString())
        val repo = engine(saved) { wire.client() }
        repo.request(cast)
        val answer = settled(repo)
        assertTrue(answer.remote)
        val reading = requireNotNull(answer.reading)
        assertEquals(concise["answer"]!!.jsonPrimitive.content, reading.summary)
        assertEquals(concise["reason"]!!.jsonPrimitive.content, reading.reason)
        assertEquals(concise["advice"]!!.jsonPrimitive.content, reading.advice)
        assertEquals(cast.question, reading.question)
        assertEquals(listOf("赤口", "赤口", "小吉"), reading.stages.map { it.palace })
        assertEquals(listOf(LiuRenRelation.SAME, LiuRenRelation.GENERATES), reading.links.map { it.relation })
        assertEquals(1, wire.requests.size)
        assertNotNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
    }

    @Test fun annualRomanceAnswerKeepsTheOriginalQuestionHorizonAndEightFourZeroCourse() = runBlocking {
        // A protocol fixture protects intent/persistence, not a claim about actual model quality.
        val original = cast.copy(question = "今年能找到女朋友并脱单吗？", digits = "840")
        val saved = store()
        saved.saveSession(LiuRenSession(original.question, original.mode, original.digits, LiuRenStep.RESULT, original))
        val fixture = buildJsonObject {
            put("answer", "今年有结识新人的机会，但从心动到稳定恋爱偏慢。")
            put("reason", "留连在两头，中间小吉，说明接触可能推进，最终确定关系仍易反复。")
            put("advice", "多参加能持续认识人的活动，愿意继续接触时主动表达兴趣。")
        }
        val wire = FakeHttp(content = fixture.toString())
        val repo = engine(saved) { wire.client() }
        repo.request(original)
        val answer = settled(repo, original)
        assertTrue(answer.remote)
        val reading = requireNotNull(answer.reading)
        assertEquals(fixture["answer"]!!.jsonPrimitive.content, reading.summary)
        assertEquals(fixture["reason"]!!.jsonPrimitive.content, reading.reason)
        assertEquals(original.question, reading.question)
        assertEquals(original, saved.session().cast)
        assertEquals(listOf("留连", "小吉", "留连"), reading.stages.map { it.palace })
        assertEquals(listOf(LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY), reading.links.map { it.relation })
        val input = parseInput(wire.requests.single())
        assertEquals(original.question, input["question"]!!.jsonPrimitive.content)
        assertEquals("840", input["reported_digits"]!!.jsonPrimitive.content)
        assertEquals(listOf(8, 4, 10), input["counts"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf("留连", "小吉", "留连"), input["palaces"]!!.jsonArray.map { it.jsonPrimitive.content })
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
        val valid = validReading(cast.question)
        fun changed(name: String, value: JsonElement) = JsonObject(valid.toMutableMap().apply { put(name, value) }).toString()
        val badBodies = linkedMapOf(
            "old reply-only response" to onlyTerminal,
            "missing answer" to JsonObject(valid - "answer").toString(),
            "missing reason" to JsonObject(valid - "reason").toString(),
            "missing advice" to JsonObject(valid - "advice").toString(),
            "blank answer" to changed("answer", JsonPrimitive("  \n  ")),
            "null reason" to changed("reason", JsonNull),
            "wrong field shape" to changed("answer", buildJsonArray { add("面试有推进空间") }),
            "reason describes another course" to changed("reason", JsonPrimitive("大安到速喜的组合说明面试会收到明确消息，先核对材料与后续通知。")),
            "guaranteed outcome" to changed("answer", JsonPrimitive("这次面试肯定会被录用，保证没有任何困难。")),
            "guaranteed advice" to changed("advice", JsonPrimitive("准备面试材料后肯定会被录用，保证没有任何困难。")),
            "malformed JSON" to "{ malformed JSON",
            "empty content" to "",
        )
        for ((label, bad) in badBodies) {
            val saved = store()
            saved.saveSession(LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast))
            val wire = FakeHttp(content = bad)
            val repo = engine(saved) { wire.client() }
            repo.request(cast)
            val failed = settled(repo)
            assertFalse("$label must not replace the local reading", failed.remote)
            assertNotNull(label, failed.error)
            assertNotNull(failed.reading)
            assertTrue(failed.reply.contains("面试"))
            assertEquals(cast, saved.session().cast)
            assertNull(label, saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
            assertEquals(label, 1, wire.requests.size)
            repeat(3) { repo.state(cast) }
            val reopened = engine(saved) { wire.client() }
            assertFalse(reopened.state(cast).value.remote)
            assertEquals(1, wire.requests.size) // Merely reopening a failed answer does not buy a repair.
            wire.content = null // A later explicit retry receives the qualified concise answer.
            reopened.request(cast)
            val retried = settled(reopened)
            assertTrue(label, retried.remote)
            assertEquals(valid["answer"]!!.jsonPrimitive.content, retried.reading!!.summary)
            assertEquals(valid["reason"]!!.jsonPrimitive.content, retried.reading!!.reason)
            assertEquals(3, requireNotNull(retried.reading).stages.size)
            assertEquals(2, retried.reading!!.links.size)
            assertNotNull(saved.analysis(XiaoLiuRenAnalysisRepository.key(cast)))
            assertEquals(2, wire.requests.size)
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
    }

    private class FakeHttp(@Volatile var status: Int = 200, @Volatile var content: String? = null) {
        val requests = CopyOnWriteArrayList<JsonObject>()
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
            return DeepSeekClient("synthetic-only", profile, http)
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
            put("answer", if (travel) "出行安排有逐步落实的余地，先把时间与路线确认清楚。"
                else "这次面试宜先把表达准备清楚，再用具体经历回应问题。")
            put("reason", if (travel) "前两段赤口强调沟通，后段小吉是小进展；所以先确认安排，再看行程是否落实。"
                else "前两段赤口提醒准备与现场沟通都要留意措辞，后段小吉是争取具体反馈，不能当成录用已定。")
            put("advice", if (travel) "核对票证和路线，再准备一条可执行的备选安排。"
                else "准备两段相关经历，练习清楚简短的回答，再问明后续通知方式。")
        }
    }
}
