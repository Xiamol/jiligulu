package com.jiligulu.app.data.repository

import android.app.Application
import com.jiligulu.app.core.ai.*
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.ui.chat.PendingDraft
import kotlinx.coroutines.runBlocking
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
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiLedgerRetrievalTest {
    private val context get() = RuntimeEnvironment.getApplication<Application>()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = at("2026-10-07") + 12 * 3600000L
    private val json = Json { encodeDefaults = true }
    @After fun close() { opened.forEach { it.close() }; names.forEach(context::deleteDatabase) }

    private inner class Fixture(vararg responses: AiParseResult) {
        val db: AppDatabase = run {
            val name = "ai-lookup-${names.size}.db"; names += name
            AppDatabase.build(context, name).also { opened += it }
        }
        val requests = mutableListOf<JsonObject>()
        val billRepository = BillRepository(db.billDao())
        val ai: AiRepository
        init {
            runBlocking { UserPrefs(context).setApiKeyOverride("fixture-not-a-real-credential") }
            var next = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                requests += json.parseToJsonElement(buffer.readUtf8()).jsonObject
                check(next < responses.size) { "Lookup must not keep asking the model in a loop" }
                val result = responses[next++]
                val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("finish_reason", "stop")
                    put("message", buildJsonObject { put("content", json.encodeToString(AiParseResult.serializer(), result)) })
                }) }) }.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toResponseBody("application/json".toMediaType())).build()
            }.build()
            ai = AiRepository(context, CategoryRepository(db.categoryDao()), billRepository, UserPrefs(context),
                ChatHistoryRepository(db), CategoryAdminRepository(db), clientFactory = { DeepSeekClient(it, client) },
                ledgerLookupRepository = LedgerLookupRepository(db))
        }
        fun body(index: Int) = requests[index]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        fun system(index: Int) = requests[index]["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        suspend fun oldBill(): Long = db.billDao().insert(BillEntity(amountFen = 1280, type = BillType.EXPENSE,
            categoryId = db.categoryDao().findByName("水果")!!.id, detail = "旧日苹果", timestamp = at("2025-01-08")))
    }

    @Test fun historicalQuestionRetrievesTheRequestedDayWithAStableSystemPrefixAndOnlyOneFollowup() = runBlocking {
        val fixture = Fixture(AiParseResult(ledgerQuery = AiLedgerQuery(startDate = "2025-01-08", endDate = "2025-01-09")),
            AiParseResult(reply = "那天买苹果12.8元，阿噜～"))
        val old = fixture.oldBill()
        repeat(220) { fixture.billRepository.addManual(100, BillType.EXPENSE, 1, "今天的记录", "", now + it) }
        val parsed = fixture.ai.parse("2025年1月8日花了多少钱？", now, zone).getOrThrow()
        assertEquals(2, fixture.requests.size)
        assertFalse(fixture.body(0).contains("旧日苹果"))
        assertTrue(fixture.body(1).contains("旧日苹果"))
        assertTrue(fixture.body(1).contains("\"total_count\":1"))
        assertEquals(fixture.system(0), fixture.system(1))
        assertEquals(setOf(old), parsed.retrievedBillIds)
        assertTrue(parsed.ledgerLookupCompleted)
        assertTrue(fixture.ai.toTurn(parsed, "2025年1月8日花了多少钱？", now, zone, PendingDraft("5")) is AiTurn.Chat)
    }

    @Test fun questionCannotAccidentallyAddTheRetrievedBillAgainOrChangeSettings() = runBlocking {
        val fixture = Fixture(AiParseResult(ledgerQuery = AiLedgerQuery(keywords = listOf("苹果"))),
            AiParseResult(reply = "查到啦", bills = listOf(AiBillDraft(amountYuan = 12.8, category = "水果", detail = "苹果")),
                appAction = AiAppAction(kind = AiAppAction.EMPTY_TRASH)))
        fixture.oldBill()
        val parsed = fixture.ai.parse("苹果以前花了多少？", now, zone).getOrThrow()
        assertTrue(parsed.bills.isEmpty()); assertNull(parsed.appAction)
        assertEquals(1, fixture.billRepository.recent(300).size)
    }

    @Test fun retrievedHistoricalIdCanCreateAnUpdateCardButNeverAppliesBeforeConfirmation() = runBlocking {
        // This fixture's first insert is id 1. The subsequent lookup explicitly supplies that id.
        val fixture = Fixture(AiParseResult(ledgerQuery = AiLedgerQuery(startDate = "2025-01-08", endDate = "2025-01-09")),
            AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = 1, amountYuan = 15.0)), reply = "核对一下～"))
        val id = fixture.oldBill(); assertEquals(1L, id)
        repeat(220) { fixture.billRepository.addManual(100, BillType.EXPENSE, 1, "新的", "", now + it) }
        val input = "把2025年1月8日苹果那笔改成15元"
        val parsed = fixture.ai.parse(input, now, zone).getOrThrow()
        val turn = fixture.ai.toTurn(parsed, input, now, zone, null)
        assertTrue(turn is AiTurn.Commands)
        assertEquals(id, (turn as AiTurn.Commands).items.single().billId)
        assertEquals(1280L, fixture.billRepository.getById(id)!!.amountFen)
        fixture.billRepository.moveToTrash(id)
        assertTrue(fixture.ai.toTurn(parsed, input, now, zone, null) is AiTurn.Chat)
    }

    @Test fun repeatedQueriesAreBoundedAndInvalidDatesDoNotFallBackToRecentRecords() = runBlocking {
        val repeated = Fixture(AiParseResult(ledgerQuery = AiLedgerQuery(keywords = listOf("苹果"))),
            AiParseResult(ledgerQuery = AiLedgerQuery(keywords = listOf("苹果"))))
        repeated.oldBill()
        val answer = repeated.ai.parse("查苹果", now, zone).getOrThrow()
        assertEquals(2, repeated.requests.size); assertTrue(answer.bills.isEmpty())
        assertTrue(answer.reply.contains("还没确定检索范围"))
        val invalid = Fixture(AiParseResult(ledgerQuery = AiLedgerQuery(startDate = "2026-02-30")))
        assertTrue(invalid.ai.parse("2月30日花费", now, zone).getOrThrow().reply.contains("不明确"))
        assertEquals(1, invalid.requests.size)
    }

    @Test fun ordinaryChatKeepsTheExistingSingleRequestPath() = runBlocking {
        val fixture = Fixture(AiParseResult(reply = "在呀，阿噜～"))
        assertEquals("在呀，阿噜～", fixture.ai.parse("早上好", now, zone).getOrThrow().reply)
        assertEquals(1, fixture.requests.size)
    }
}
