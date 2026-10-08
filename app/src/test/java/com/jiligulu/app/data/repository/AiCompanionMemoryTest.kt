package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.*
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.CompanionFact
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
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiCompanionMemoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val json = Json { encodeDefaults = true }
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = 1791331200000L
    private val student = AiMemoryUpdate("study", "大学生", "我是大学生")
    private val cycling = AiMemoryUpdate("interest", "骑车", "我喜欢骑车")
    @After fun close() { opened.forEach { it.close() }; names.forEach(context::deleteDatabase) }

    private inner class Fixture(
        val results: List<AiParseResult>,
        private val beforeReply: () -> Unit = {},
        writeMemory: (suspend (Long, List<CompanionFact>) -> Boolean)? = null
    ) {
        val prefs = UserPrefs(context)
        val requests = mutableListOf<JsonObject>()
        val db = run {
            val name = "companion-ai-${names.size}.db"; names += name
            AppDatabase.build(context, name).also { opened += it }
        }
        val ai: AiRepository
        init {
            runBlocking { prefs.clearCompanionMemories(); prefs.setCompanionMemoryEnabled(true)
                prefs.setApiKeyOverride("fixture-not-a-real-credential") }
            var next = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                requests += json.parseToJsonElement(buffer.readUtf8()).jsonObject
                beforeReply()
                check(next < results.size) { "Companion memory must not create an extra API request" }
                val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("finish_reason", "stop"); put("message", buildJsonObject {
                        put("content", json.encodeToString(AiParseResult.serializer(), results[next++]))
                    })
                }) }) }.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toResponseBody("application/json".toMediaType())).build()
            }.build()
            ai = AiRepository(context, CategoryRepository(db.categoryDao()), BillRepository(db.billDao()), prefs,
                ChatHistoryRepository(db), CategoryAdminRepository(db), clientFactory = { DeepSeekClient(it, client) },
                ledgerLookupRepository = LedgerLookupRepository(db),
                rememberCompanionFacts = writeMemory ?: { revision, facts -> prefs.rememberCompanionFactsIfCurrent(revision, facts) })
        }
        fun content(request: Int, message: Int) = requests[request]["messages"]!!.jsonArray[message].jsonObject["content"]!!.jsonPrimitive.content
        fun currentContent(request: Int) = requests[request]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
    }

    @Test fun selfDisclosureIsLearnedWithinTheExistingRoundAndOnlyDynamicContextChangesNextTime() = runBlocking {
        val fixture = Fixture(listOf(AiParseResult(reply = "原来如此，阿噜～", memoryUpdates = listOf(student, cycling)),
            AiParseResult(reply = "今天骑车了吗，阿噜～")))
        fixture.ai.parse("我是大学生，我喜欢骑车", now, zone).getOrThrow()
        assertEquals(1, fixture.requests.size)
        assertEquals(setOf("大学生", "骑车"), fixture.prefs.companionMemory.first().facts.map { it.value }.toSet())
        fixture.ai.parse("早上好", now + 1000, zone).getOrThrow()
        assertEquals(2, fixture.requests.size)
        assertEquals(fixture.content(0, 0), fixture.content(1, 0))
        assertEquals("The low-frequency cached account segment also stays unchanged", fixture.content(0, 1), fixture.content(1, 1))
        assertTrue(fixture.currentContent(1).contains("\"value\":\"大学生\""))
        assertFalse(fixture.content(1, 0).contains("\"facts\":[{\"kind\":\"study\""))
    }

    @Test fun retrievalRoundsLearnOnceAndDoNotTreatLedgerFactsAsPersonalEvidence() = runBlocking {
        val fixture = Fixture(listOf(AiParseResult(ledgerQuery = AiLedgerQuery(), memoryUpdates = listOf(student)),
            AiParseResult(reply = "没有查到，阿噜～", memoryUpdates = listOf(student,
                AiMemoryUpdate("gender", "男", "买了棋盘")))))
        val revision = fixture.prefs.companionMemory.first().revision
        fixture.ai.parse("我是大学生，帮我查一下旧账单", now, zone).getOrThrow()
        val after = fixture.prefs.companionMemory.first()
        assertEquals(2, fixture.requests.size)
        assertEquals(revision + 1, after.revision)
        assertEquals(listOf("大学生"), after.facts.map { it.value })
    }

    @Test fun aDisabledOrClearedSettingDuringTheReplyCannotBeResurrected() = runBlocking {
        val fixture = Fixture(listOf(AiParseResult(reply = "好呀", memoryUpdates = listOf(cycling))), beforeReply = {
            runBlocking { UserPrefs(context).clearCompanionMemories(); UserPrefs(context).setCompanionMemoryEnabled(false) }
        })
        assertTrue(fixture.ai.parse("我喜欢骑车", now, zone).isSuccess)
        val state = fixture.prefs.companionMemory.first()
        assertFalse(state.enabled); assertTrue(state.facts.isEmpty())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun aFailedLocalCompareAndSetCannotAdoptTheClearedSettingsRevision() = runBlocking {
        var firstWrite = true
        val fixture = Fixture(listOf(AiParseResult(reply = "好呀", memoryUpdates = listOf(cycling))), writeMemory = { revision, facts ->
            val prefs = UserPrefs(context)
            if (firstWrite) { firstWrite = false; prefs.clearCompanionMemories() }
            prefs.rememberCompanionFactsIfCurrent(revision, facts)
        })
        val initial = fixture.prefs.companionMemory.first().revision
        fixture.ai.parse("我喜欢骑车", now, zone).getOrThrow()
        val after = fixture.prefs.companionMemory.first()
        assertEquals(initial + 1, after.revision)
        assertTrue(after.facts.isEmpty())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun aClearImmediatelyAfterLocalSuccessStillInvalidatesTheLaterApiUpdate() = runBlocking {
        var firstWrite = true
        val fixture = Fixture(listOf(AiParseResult(reply = "好呀", memoryUpdates = listOf(cycling))), writeMemory = { revision, facts ->
            val prefs = UserPrefs(context)
            val applied = prefs.rememberCompanionFactsIfCurrent(revision, facts)
            if (firstWrite) { firstWrite = false; prefs.clearCompanionMemories() }
            applied
        })
        fixture.ai.parse("我是大学生，我喜欢骑车", now, zone).getOrThrow()
        assertTrue(fixture.prefs.companionMemory.first().facts.isEmpty())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun memoryWriteFailureDoesNotDiscardAnOtherwiseValidBillDraft() = runBlocking {
        listOf(java.io.IOException("fixture"), CancellationException("optional store only")).forEach { failure ->
            val fixture = Fixture(listOf(AiParseResult(bills = listOf(AiBillDraft(amountYuan = 8.0, detail = "午饭", category = "吃饭")),
                reply = "核对一下～", memoryUpdates = listOf(student))), writeMemory = { _, _ -> throw failure })
            val input = "我是大学生，今天午饭8元"
            val result = fixture.ai.parse(input, now, zone).getOrThrow()
            assertEquals("午饭", result.bills.single().detail)
            assertTrue(fixture.ai.toTurn(result, input, now, zone, null) is AiTurn.Drafts)
            assertTrue(fixture.prefs.companionMemory.first().facts.isEmpty())
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun cancellingTheApiReplyDoesNotEraseTheUsersAlreadySentSelfDisclosure() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = Fixture(listOf(AiParseResult(reply = "好呀", memoryUpdates = listOf(cycling))), beforeReply = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val request = async(Dispatchers.Default) { fixture.ai.parse("我喜欢骑车", now, zone) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        request.cancel(); release.countDown(); request.join()
        assertEquals(listOf("骑车"), fixture.prefs.companionMemory.first().facts.map { it.value })
    }

    @Test fun aValidReplyWithoutOptionalMemoryMetadataStillLearnsAndReopens() = runBlocking {
        val fixture = Fixture(listOf(AiParseResult(reply = "记住啦")))
        fixture.ai.parse("请记住：我是大学生，我喜欢骑车", now, zone).getOrThrow()
        assertEquals(setOf("大学生", "骑车"), UserPrefs(context).companionMemory.first().facts.map { it.value }.toSet())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun missingApiCredentialDoesNotPreventExplicitLocalMemory() = runBlocking {
        val fixture = Fixture(emptyList())
        fixture.prefs.setApiKeyOverride("")
        runCatching { fixture.ai.parse("我是大学生", now, zone) }
        assertEquals(listOf("大学生"), fixture.prefs.companionMemory.first().facts.map { it.value })
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun imageReceiptPathsNeverLearnPersonalFactsFromOcrOrStartAnExtraRequest() = runBlocking {
        val fixture = Fixture(emptyList())
        val text = ImageReceiptCodec.render("""{"events":[{"kind":"transaction","direction":"INCOME","amount":"50","counterparty":"家人","detail":"家人的红包","status":"已存入零钱"}]}""", now, zone)
        assertTrue(fixture.ai.parse(text, now, zone).isSuccess)
        assertEquals(0, fixture.requests.size)
        assertTrue(fixture.prefs.companionMemory.first().facts.isEmpty())
    }
}
