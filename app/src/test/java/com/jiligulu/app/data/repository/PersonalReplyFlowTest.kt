package com.jiligulu.app.data.repository

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.jiligulu.app.core.ai.*
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.chat.PromptRenderer
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.QuipLibrary
import com.jiligulu.app.ui.chat.ChatItem
import com.jiligulu.app.ui.chat.ChatViewModel
import com.jiligulu.app.ui.chat.PendingDraft
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PersonalReplyFlowTest {
    private val context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val stores = mutableListOf<ViewModelStore>()
    private val json = Json { encodeDefaults = true }
    @After fun close() { stores.forEach { it.clear() }; Dispatchers.resetMain(); opened.forEach { it.close() }; names.forEach(context::deleteDatabase) }

    private inner class Fixture(val response: AiParseResult = AiParseResult(reply = "阿噜听着呢"), val offline: Boolean = false,
        laterResponses: List<AiParseResult> = emptyList()) {
        val prefs = UserPrefs(context)
        val requests = mutableListOf<JsonObject>()
        val db = AppDatabase.build(context, "personal-flow-${names.size}.db".also(names::add)).also(opened::add)
        val history = ChatHistoryRepository(db)
        val categories = CategoryRepository(db.categoryDao())
        val ai: AiRepository
        init {
            runBlocking { prefs.clearCompanionMemories(); prefs.setCompanionMemoryEnabled(true); prefs.setApiKeyOverride("fixture-not-a-real-credential") }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                requests += json.parseToJsonElement(buffer.readUtf8()).jsonObject
                if (offline) throw IOException("offline fixture")
                val reply = (listOf(response) + laterResponses).getOrElse(requests.lastIndex) { response }
                val body = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("finish_reason", "stop"); put("message", buildJsonObject {
                        put("content", json.encodeToString(AiParseResult.serializer(), reply))
                    })
                }) }) }.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            ai = AiRepository(context, categories, BillRepository(db.billDao()), prefs, history,
                CategoryAdminRepository(db), clientFactory = { DeepSeekClient(it, client) })
        }
        suspend fun ageQuestion() {
            val now = System.currentTimeMillis()
            history.insert(ChatMessageEntity(kind = "USER", content = "你猜我多少岁？", createdAt = now - 2000))
            history.insert(ChatMessageEntity(kind = "ASSISTANT", content = "猜十九二十岁，你告诉阿噜吧", createdAt = now - 1000))
        }
        suspend fun model(): ChatViewModel {
            Dispatchers.setMain(Dispatchers.Unconfined)
            return ChatViewModel(ai, categories, PersonaEngine(QuipLibrary(emptyList())), history).also { vm ->
                ViewModelStore().also { stores += it; it.put("chat", vm) }
                withTimeout(8000) { vm.ready.first { it } }
            }
        }
        suspend fun send(vm: ChatViewModel, input: String) {
            vm.send(input)
            withTimeout(8000) { vm.sending.first { !it } }
            assertNull(vm.error.value)
        }
    }

    @Test fun ageAnswerOverridesFalseModelBillsAndPendingWithinTheSameHttpRound() = runBlocking {
        val fixture = Fixture(AiParseResult(bills = listOf(AiBillDraft(amountYuan = 19.0, detail = "未知")),
            pending = AiPendingDraft(amountYuan = 19.0), reply = "这19元花在哪儿？"))
        fixture.ageQuestion()
        val vm = fixture.model(); fixture.send(vm, "19")
        assertEquals("19岁", fixture.prefs.companionMemory.first().facts.single { it.kind == "age" }.value)
        assertNull(fixture.history.latestPending())
        assertNull(vm.pending.value)
        assertTrue(vm.items.value.none { it is ChatItem.DraftCard })
        assertTrue(fixture.db.billDao().observeAll().first().isEmpty())
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.requests.single()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content.contains("没有记账请求"))
    }

    @Test fun offlinePersonalAnswersStillSaveGenderAgeAndBirthdayWithoutGeneratingABill() = runBlocking {
        val fixture = Fixture(offline = true); fixture.ageQuestion()
        val vm = fixture.model(); fixture.send(vm, "19")
        fixture.send(vm, "男生"); fixture.send(vm, "生日2月28")
        assertEquals(mapOf("age" to "19岁", "gender" to "男", "birthday" to "2月28日"),
            fixture.prefs.companionMemory.first().facts.associate { it.kind to it.value })
        assertNull(vm.pending.value)
        assertNull(fixture.history.latestPending())
        assertTrue(vm.items.value.none { it is ChatItem.DraftCard })
        assertTrue(fixture.db.billDao().observeAll().first().isEmpty())
    }

    @Test fun anAgeCorrectionDismissesOnlyTheMatchingFalseAmountPending() = runBlocking {
        val fixture = Fixture(); val now = System.currentTimeMillis()
        fixture.history.suspendPending(PromptRenderer.encodePending(PendingDraft("19", rawInput = "19", createdAt = now)), now)
        val vm = fixture.model(); assertEquals("19", vm.pending.value!!.amountText)
        fixture.send(vm, "没有19的账单，我说的是19岁")
        assertNull(vm.pending.value); assertNull(fixture.history.latestPending())
        assertEquals("19岁", fixture.prefs.companionMemory.first().facts.single().value)
        assertTrue(vm.items.value.none { it is ChatItem.DraftCard })
        fixture.history.suspendPending(PromptRenderer.encodePending(PendingDraft("10", rawInput = "10", createdAt = now)), now)
        // A separately constructed VM restores the independent unfinished amount.
        val second = fixture.model(); fixture.send(second, "男生")
        assertEquals("10", second.pending.value!!.amountText)
        assertEquals("10", PromptRenderer.pendingOf(fixture.history.latestPending())!!.amountText)
    }

    @Test fun foodCommaAmountKeepsARealTenYuanDraftOnlineAndOffline() = runBlocking {
        for (offline in listOf(false, true)) {
            val fixture = Fixture(AiParseResult(bills = listOf(AiBillDraft(amountYuan = 10.0, detail = "吃饭"))), offline)
            val vm = fixture.model(); fixture.send(vm, "吃饭，10")
            val card = vm.items.value.filterIsInstance<ChatItem.DraftCard>().single()
            assertEquals("10", card.drafts.single().amountText)
            assertEquals("吃饭", card.drafts.single().detail)
            assertTrue(fixture.prefs.companionMemory.first().facts.isEmpty())
            assertNull(vm.pending.value)
        }
    }

    @Test fun mixedAgeAndFoodUsesOnlyTheFoodAmountInOfflineBookkeeping() = runBlocking {
        val fixture = Fixture(offline = true); val vm = fixture.model()
        fixture.send(vm, "我19岁，吃饭10元")
        assertEquals("19岁", fixture.prefs.companionMemory.first().facts.single().value)
        assertEquals("10", vm.items.value.filterIsInstance<ChatItem.DraftCard>().single().drafts.single().amountText)
    }

    @Test fun theSameNineteenInAgeAndAnExpenseNeverDeletesTheRealExpense() = runBlocking {
        for (offline in listOf(false, true)) {
            val fixture = Fixture(AiParseResult(bills = listOf(AiBillDraft(amountYuan = 19.0, detail = "吃饭"))), offline)
            val vm = fixture.model(); fixture.send(vm, "我19岁，吃饭19元")
            assertEquals("19岁", fixture.prefs.companionMemory.first().facts.single().value)
            assertEquals("19", vm.items.value.filterIsInstance<ChatItem.DraftCard>().single().drafts.single().amountText)
        }
    }

    @Test fun savingARealMealEndsTheEarlierAgeQuestionBeforeTheNextBareNumber() = runBlocking {
        val fixture = Fixture(AiParseResult(bills = listOf(AiBillDraft(amountYuan = 10.0, detail = "吃饭"))),
            laterResponses = listOf(AiParseResult(pending = AiPendingDraft(amountYuan = 19.0), reply = "这笔花在哪里？")))
        fixture.ageQuestion()
        val vm = fixture.model(); fixture.send(vm, "吃饭，10")
        val card = vm.items.value.filterIsInstance<ChatItem.DraftCard>().single()
        vm.confirmCard(card.id)
        withTimeout(8000) { vm.items.first { rows -> rows.filterIsInstance<ChatItem.DraftCard>().any { it.id == card.id && it.status == ChatItem.DraftCard.Status.CONFIRMED } } }
        fixture.send(vm, "19")
        assertEquals("19", vm.pending.value!!.amountText)
        assertEquals("19", PromptRenderer.pendingOf(fixture.history.latestPending())!!.amountText)
        assertTrue(fixture.prefs.companionMemory.first().facts.isEmpty())
        assertEquals(listOf(1000L), fixture.db.billDao().observeAll().first().map { it.amountFen })
        assertEquals(2, fixture.requests.size)
    }

    @Test fun birthdayDoesNotTurnModelSchoolGuessesOrAssistantPromisesIntoFacts() = runBlocking {
        val fixture = Fixture(AiParseResult(reply = "记住啦，你在星河大学读大二", memoryUpdates = listOf(
            AiMemoryUpdate("school", "星河大学", "星河大学"), AiMemoryUpdate("grade", "大二", "大二"))))
        fixture.history.insert(ChatMessageEntity(kind = "ASSISTANT", content = "你是星河大学大二的男生吧，我记住了", createdAt = System.currentTimeMillis()))
        val vm = fixture.model(); fixture.send(vm, "生日2月28")
        assertEquals(listOf("2月28日"), fixture.prefs.companionMemory.first().facts.map { it.value })
        assertEquals(1, fixture.requests.size)
    }
}
