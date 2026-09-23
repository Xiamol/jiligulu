package com.jiligulu.app.data.repository

import android.content.Context
import com.jiligulu.app.core.ai.AiAppAction
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.domain.chat.ChatContext
import com.jiligulu.app.domain.chat.PromptRenderer
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.NavTargets
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.ui.chat.AppActionCodec
import com.jiligulu.app.ui.chat.AppActionPayload
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.CommandCardPayload
import com.jiligulu.app.ui.chat.CommandItem
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = JiliguluApp::class)
class AppActionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<Pair<String, AppDatabase>>()
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 9, 23, 15, 0).atZone(zone).toInstant().toEpochMilli()

    @After fun tearDown() {
        opened.forEach { (name, db) -> db.close(); context.deleteDatabase(name) }
    }

    private inner class Fixture(synchronize: (suspend (AiAppAction) -> Unit)? = null) {
        val db = AppDatabase.build(context, "actions-${opened.size}.db").also { opened += "actions-${opened.size}.db" to it }
        val bills = BillRepository(db.billDao())
        val history = ChatHistoryRepository(db)
        val prefs = UserPrefs(context)
        val ai = if (synchronize == null) AiRepository(context, CategoryRepository(db.categoryDao()), bills, prefs, history, CategoryAdminRepository(db))
            else AiRepository(context, CategoryRepository(db.categoryDao()), bills, prefs, history, CategoryAdminRepository(db), synchronize)
        suspend fun bill(name: String): Long = db.billDao().insert(BillEntity(
            amountFen = 900, type = BillType.EXPENSE, categoryId = 1, detail = name, timestamp = now
        ))
        suspend fun action(action: AiAppAction): AiTurn = ai.toTurn(AiParseResult(appAction = action), "调整设置", now, zone, null)
        suspend fun store(payload: AppActionPayload, status: String = "EDITING"): Long = history.insert(ChatMessageEntity(
            kind = "APP_ACTION", draftPayload = AppActionCodec.encode(payload), status = status, createdAt = now
        ))
    }

    @Test fun `old json remains compatible and invalid actions fail closed`() {
        assertNull(Json.decodeFromString(AiParseResult.serializer(), "{\"reply\":\"你好\"}").appAction)
        assertFalse(AiAppAction(kind = "delete_everything").isValid)
        assertFalse(AiAppAction(kind = AiAppAction.WATER_SETTINGS).isValid)
        listOf(0, -1, 780, Int.MAX_VALUE).forEach {
            assertFalse(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = it).isValid)
        }
        assertFalse(AiAppAction(AiAppAction.WATER_SETTINGS, quietStartMinutes = 1440).isValid)
        assertFalse(AiAppAction(AiAppAction.WATER_SETTINGS, quietEnabled = false, quietStartMinutes = 300).isValid)
        assertFalse(AiAppAction(AiAppAction.EMPTY_TRASH, enabled = false).isValid)
        assertTrue(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 1).isValid)
        assertTrue(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 779).isValid)
        assertNull(AppActionCodec.decode("{\"version\":99,\"action\":{\"kind\":\"empty_trash\"}}"))
    }

    @Test fun `proposing settings never changes preferences and rejects mixed actions`() = runBlocking {
        val f = Fixture()
        f.prefs.setWaterSettings(enabled = false, intervalMinutes = 60)
        val turn = f.action(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 30)) as AiTurn.AppAction
        assertEquals(60, f.prefs.waterIntervalMinutes.first())
        assertTrue(turn.payload.summary.contains("60 → 30"))
        val mixed = f.ai.toTurn(AiParseResult(appAction = turn.payload.action, bills = listOf(AiBillDraft(amountYuan = 5.0))), "测试", now, zone, null)
        assertTrue(mixed is AiTurn.Chat)
        assertTrue(f.action(AiAppAction("unknown")) is AiTurn.Chat)
        assertEquals(60, f.prefs.waterIntervalMinutes.first())
    }

    @Test fun `settings require confirmed persisted card and are not replayed`() = runBlocking {
        val f = Fixture()
        f.prefs.setWaterSettings(enabled = false, intervalMinutes = 60, quietStartMinutes = 1380, quietEndMinutes = 480)
        val payload = (f.action(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 30)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        f.ai.commitAppAction(card)
        assertEquals(30, f.prefs.waterIntervalMinutes.first())
        assertEquals(1380, f.prefs.quietStartMinutes.first())
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
        f.prefs.setWaterSettings(intervalMinutes = 45)
        f.ai.commitAppAction(card)
        assertEquals("Old confirmation cannot overwrite a newer setting", 45, f.prefs.waterIntervalMinutes.first())
        val cancelled = f.store(payload, status = "DISMISSED")
        f.ai.commitAppAction(cancelled)
        assertEquals(45, f.prefs.waterIntervalMinutes.first())
    }

    @Test fun `quiet hour card applies the exact time range that it displayed`() = runBlocking {
        val f = Fixture()
        f.prefs.setWaterSettings(enabled = false, quietStartMinutes = 1380, quietEndMinutes = 480)
        val payload = (f.action(AiAppAction(AiAppAction.WATER_SETTINGS, quietStartMinutes = 1320)) as AiTurn.AppAction).payload
        assertTrue(payload.summary.contains("22:00—08:00"))
        assertEquals(480, payload.action.quietEndMinutes)
        val card = f.store(payload)
        f.prefs.setWaterSettings(quietEndMinutes = 540)
        f.ai.commitAppAction(card)
        assertEquals(1320, f.prefs.quietStartMinutes.first())
        assertEquals(480, f.prefs.quietEndMinutes.first())
    }

    @Test fun `trash confirmation only purges the proposed snapshot and never restored or new bills`() = runBlocking {
        val f = Fixture()
        val old = f.bill("旧奶茶")
        val restored = f.bill("要恢复的")
        val live = f.bill("正常账单")
        f.bills.moveToTrash(listOf(old, restored), now)
        val payload = (f.action(AiAppAction(AiAppAction.EMPTY_TRASH)) as AiTurn.AppAction).payload
        assertEquals(setOf(old, restored), payload.trashIds.toSet())
        assertNotNull(f.bills.getById(old))
        val card = f.store(payload)
        val addedLater = f.bill("之后新删的")
        f.bills.moveToTrash(addedLater, now + 1)
        f.bills.restore(restored)
        f.ai.commitAppAction(card)
        assertNull(f.bills.getById(old))
        assertNotNull(f.bills.getById(restored))
        assertNotNull(f.bills.getById(live))
        assertNotNull(f.bills.getById(addedLater))
        assertEquals(1, AppActionCodec.decode(f.history.getById(card)!!.draftPayload)!!.appliedCount)
        f.ai.commitAppAction(card)
        assertNotNull(f.bills.getById(addedLater))
    }

    @Test fun `purge and confirmation marker roll back together on storage failure`() = runBlocking {
        val f = Fixture()
        val id = f.bill("保留的账")
        f.bills.moveToTrash(id, now)
        val payload = (f.action(AiAppAction(AiAppAction.EMPTY_TRASH)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_action BEFORE UPDATE ON chat_messages WHEN NEW.kind = 'APP_ACTION' AND NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
        assertTrue(runCatching { f.ai.commitAppAction(card) }.isFailure)
        assertNotNull(f.bills.getById(id))
        assertEquals("EDITING", f.history.getById(card)!!.status)
    }

    @Test fun `a restored then deleted again bill is outside an old trash confirmation`() = runBlocking {
        val f = Fixture()
        val id = f.bill("旧账")
        f.bills.moveToTrash(id, now)
        val payload = (f.action(AiAppAction(AiAppAction.EMPTY_TRASH)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        f.bills.restore(id)
        f.bills.updateDetails(id, 1500, "修改过的新账", now + 10)
        f.bills.moveToTrash(id, now + 100)
        f.ai.commitAppAction(card)
        assertEquals(1500L, f.bills.getById(id)!!.amountFen)
        assertEquals(now + 100, f.bills.getById(id)!!.deletedAt)
        val legacy = f.store(payload.copy(trashDeletedAt = emptyMap()))
        assertTrue(runCatching { f.ai.commitAppAction(legacy) }.isFailure)
        assertNotNull(f.bills.getById(id))
    }

    @Test fun `scheduler failure cannot make saved settings appear cancelled`() = runBlocking {
        val f = Fixture { throw IllegalStateException("alarm scheduling failed") }
        f.prefs.setWaterSettings(enabled = false, intervalMinutes = 60)
        val payload = (f.action(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 30)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        val reply = f.ai.commitAppAction(card)
        assertEquals(30, f.prefs.waterIntervalMinutes.first())
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
        assertTrue(reply.contains("设置已经保存"))
    }

    @Test fun `leaving chat after preference commit still finishes the durable marker`() = runBlocking {
        val enteredScheduler = CompletableDeferred<Unit>()
        val releaseScheduler = CompletableDeferred<Unit>()
        val f = Fixture { enteredScheduler.complete(Unit); releaseScheduler.await() }
        f.prefs.setWaterSettings(enabled = false, intervalMinutes = 60)
        val payload = (f.action(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 30)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        val job = launch(Dispatchers.Default) { f.ai.commitAppAction(card) }
        enteredScheduler.await()
        job.cancel()
        releaseScheduler.complete(Unit)
        job.join()
        assertEquals(30, f.prefs.waterIntervalMinutes.first())
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
    }

    @Test fun `failure writing completed marker keeps an explicit recoverable state`() = runBlocking {
        val f = Fixture { }
        f.prefs.setWaterSettings(enabled = false, intervalMinutes = 60)
        val payload = (f.action(AiAppAction(AiAppAction.WATER_SETTINGS, intervalMinutes = 30)) as AiTurn.AppAction).payload
        val card = f.store(payload)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_complete BEFORE UPDATE ON chat_messages WHEN NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
        assertTrue(runCatching { f.ai.commitAppAction(card) }.isFailure)
        assertEquals(30, f.prefs.waterIntervalMinutes.first())
        assertEquals("APPLYING", f.history.getById(card)!!.status)
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_complete")
        f.ai.commitAppAction(card)
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
    }

    @Test fun `update check is a real navigation request without claiming success`() = runBlocking {
        val f = Fixture()
        val turn = f.ai.toTurn(AiParseResult(navigate = NavTargets.CHECK_UPDATE, reply = "已经是最新版"), "检查更新", now, zone, null) as AiTurn.Choices
        assertFalse(turn.reply.contains("已经是最新版"))
        assertTrue(turn.reply.contains("开始检查"))
        assertEquals(NavTargets.CHECK_UPDATE, turn.options.single().action)
    }

    @Test fun `unknown command kind and corrupted stored payload cannot be applied`() = runBlocking {
        val f = Fixture()
        val id = f.bill("午饭")
        val payload = CommandCardPayload(kind = "unknown", items = listOf(CommandItem(billId = id, title = "午饭", newAmountText = "88")))
        val card = f.history.insert(ChatMessageEntity(kind = "COMMAND", draftPayload = CommandCardCodec.encode(payload), status = "EDITING"))
        assertTrue(runCatching { f.ai.commitCommands(card, payload) }.isFailure)
        assertEquals(900L, f.bills.getById(id)!!.amountFen)
        f.history.update(f.history.getById(card)!!.copy(draftPayload = "broken"))
        assertTrue(runCatching { f.ai.commitCommands(card, payload.copy(kind = "UPDATE")) }.isFailure)
        assertEquals(900L, f.bills.getById(id)!!.amountFen)
    }

    @Test fun `partially obsolete bill commands finish once instead of replaying old targets`() = runBlocking {
        val f = Fixture()
        val gone = f.bill("先删的")
        val target = f.bill("后删的")
        val payload = CommandCardPayload(kind = "DELETE", items = listOf(CommandItem(gone, "先删的"), CommandItem(target, "后删的")))
        val card = f.history.insert(ChatMessageEntity(kind = "COMMAND", draftPayload = CommandCardCodec.encode(payload), status = "EDITING"))
        f.bills.moveToTrash(gone, now)
        assertEquals(1, f.ai.commitCommands(card, payload))
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
        f.bills.restore(target)
        assertEquals(0, f.ai.commitCommands(card, payload))
        assertNull(f.bills.getById(target)!!.deletedAt)
    }

    @Test fun `name only updates keep original amount and time while explicit time edits apply`() = runBlocking {
        val f = Fixture()
        val id = f.bill("牛肉面")
        val rename = f.ai.toTurn(AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = id, detail = "米饭"))),
            "昨天那碗牛肉面改成米饭", now, zone, null) as AiTurn.Commands
        assertEquals("9", rename.items.single().newAmountText)
        assertEquals(now, rename.items.single().newTimestamp)
        val retime = f.ai.toTurn(AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = id, timeExpression = "昨天中午"))),
            "那笔账时间改成昨天中午", now, zone, null) as AiTurn.Commands
        val expected = LocalDateTime.of(2026, 9, 22, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, retime.items.single().newTimestamp)
        val card = f.history.insert(ChatMessageEntity(kind = "COMMAND", draftPayload = CommandCardCodec.encode(retime.kind, retime.items), status = "EDITING"))
        f.ai.commitCommands(card, CommandCardPayload(kind = retime.kind.name, items = retime.items))
        assertEquals(expected, f.bills.getById(id)!!.timestamp)
        assertEquals(900L, f.bills.getById(id)!!.amountFen)
    }

    @Test fun `unknown bill action does not silently create an add draft`() = runBlocking {
        val f = Fixture()
        assertTrue(f.ai.toTurn(AiParseResult(bills = listOf(AiBillDraft(action = "purge", amountYuan = 9.0))), "测试", now, zone, null) is AiTurn.Chat)
    }

    @Test fun `legacy partly applied cards never guess which targets remain`() = runBlocking {
        val f = Fixture()
        val first = f.bill("旧卡第一笔")
        val second = f.bill("旧卡第二笔")
        val payload = CommandCardPayload(kind = "DELETE", items = listOf(CommandItem(first, "一"), CommandItem(second, "二")), alreadyApplied = 1)
        val card = f.history.insert(ChatMessageEntity(kind = "COMMAND", draftPayload = CommandCardCodec.encode(payload), status = "EDITING"))
        assertEquals(0, f.ai.commitCommands(card, payload))
        assertEquals("CONFIRMED", f.history.getById(card)!!.status)
        assertNull(f.bills.getById(first)!!.deletedAt)
        assertNull(f.bills.getById(second)!!.deletedAt)
    }

    @Test fun `confirming an old draft never recreates its deleted category`() = runBlocking {
        val f = Fixture()
        val draft = DraftUi(amountText = "9", categoryName = "已经删掉的类别", detail = "午饭")
        val card = f.history.insert(ChatMessageEntity(kind = "DRAFT", draftPayload = DraftHistoryCodec.encode(listOf(draft)), status = "EDITING"))
        val item = ConfirmItem("9", BillType.EXPENSE, draft.categoryName, false, "", "", "", "午饭", "", true)
        f.ai.confirm(card, listOf(item), "午饭9块")
        assertFalse(CategoryRepository(f.db.categoryDao()).getAll().any { it.name == draft.categoryName })
        assertEquals(CategoryAdminRepository(f.db).vacuumId(), f.bills.recent(1).single().categoryId)
    }

    @Test fun `app cards never leak into frozen user assistant history`() {
        val messages = listOf(
            ChatMessageEntity(id = 1, kind = "USER", content = "清空回收站", createdAt = now),
            ChatMessageEntity(id = 2, kind = "ASSISTANT", content = "请确认", createdAt = now),
            ChatMessageEntity(id = 3, kind = "APP_ACTION", content = "卡片状态", draftPayload = "whatever", status = "CONFIRMED", createdAt = now)
        )
        val before = AiRepository.chatTurnsFor(messages, now)
        val after = AiRepository.chatTurnsFor(messages.dropLast(1), now)
        assertEquals(before, after)
        assertEquals(listOf("清空回收站", "请确认"), before.map { it.content })
    }

    @Test fun `current reminder settings only alter the dynamic context`() {
        val system = context.assets.open("prompts/parse_bill_system.txt").bufferedReader().use { it.readText() }
        val template = context.assets.open("prompts/parse_bill_context.txt").bufferedReader().use { it.readText() }
        fun renderer(settings: String) = PromptRenderer(system, template, emptyList(), ChatContext("2026-09-23 15:00", zone.id, emptyList()),
            "主人", "", PromptRenderer.CandidateBills(), null, zone, appSettings = settings)
        val first = renderer("喝水提醒：开启；间隔：60 分钟；免打扰：23:00—08:00")
        val second = renderer("喝水提醒：关闭；间隔：30 分钟；免打扰：关闭")
        assertEquals(system, first.renderSystem())
        assertEquals(first.renderSystem(), second.renderSystem())
        assertTrue(first.renderContext("现在多久提醒").contains("60 分钟"))
        assertTrue(second.renderContext("现在多久提醒").contains("30 分钟"))
        assertFalse(first.renderContext("现在多久提醒").contains("{appSettings}"))
    }
}
