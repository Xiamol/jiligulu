package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.AiTurn
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.repository.ConfirmItem
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.core.ai.AiOption
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.AiPendingDraft
import com.jiligulu.app.core.ai.IntentActions
import com.jiligulu.app.core.ai.NavTargets
import com.jiligulu.app.ui.chat.ChatViewModel
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.CommandCardPayload
import com.jiligulu.app.ui.chat.CommandItem
import com.jiligulu.app.ui.chat.CommandKind
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import com.jiligulu.app.ui.chat.PendingDraft
import com.jiligulu.app.ui.chat.PendingInputDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.app.Application
import android.content.Context
import com.jiligulu.app.domain.category.CategoryLabels
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * v0.6「阿噜真的懂上下文」这一层的验收。
 *
 * 覆盖三件用户明确抱怨过的事：
 * 1. 「我发 5，再发面条，他把面条忽略了」→ [PendingDraft] 跨轮携带
 * 2. 「他只能记账，改账删账不行」→ [AiTurn.Commands] 的命中与幻觉过滤
 * 3. 「删掉该能捞回来」→ 删除走回收站、可撤销
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ChatContextAndCommandsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    // ---------- 待补充：5 → 面条 ----------

    @Test
    fun `only an amount is detected as an incomplete entry`() {
        listOf("5", "25块", "¥88.5", " 12 元 ", "3.5块钱").forEach {
            assertTrue("「$it」应该被判为话没说完", PendingInputDetector.looksIncomplete(it))
        }
        listOf("面条", "昨天的 5 块吃饭", "5 元的面条", "记账 5", "我想买 5 个", "现在几点").forEach {
            assertFalse("「$it」不该被判为话没说完", PendingInputDetector.looksIncomplete(it))
        }
        assertEquals("5", PendingInputDetector.amountOf("5"))
        assertEquals("88.5", PendingInputDetector.amountOf("¥88.5元"))
        assertNull(PendingInputDetector.amountOf("三十"))
    }

    @Test
    fun `a suspended amount survives a restart and comes back for completion`() = runBlocking {
        val db = build("pending-suspend.db")
        val history = ChatHistoryRepository(db)
        val draft = PendingDraft(amountText = "5", rawInput = "5", createdAt = 1000)
        val id = history.suspendPending(PromptRenderer.encodePending(draft), 1000)
        db.close()

        // 冷启动：挂起账必须还在，否则用户下一条「面条」又会变成一笔无名的 5 块。
        val reopened = ChatHistoryRepository(open("pending-suspend.db"))
        val restored = PromptRenderer.pendingOf(reopened.latestPending())
        assertEquals(draft, restored)
        assertEquals(1, reopened.consumePending(id))
        assertNull(PromptRenderer.pendingOf(reopened.latestPending()))
    }

    @Test
    fun `suspending twice keeps only the newest amount`() = runBlocking {
        val db = build("pending-replace.db")
        val history = ChatHistoryRepository(db)
        history.suspendPending(PromptRenderer.encodePending(PendingDraft("5", rawInput = "5", createdAt = 1)), 1)
        history.suspendPending(PromptRenderer.encodePending(PendingDraft("88", rawInput = "88", createdAt = 2)), 2)
        // 同时挂着两笔，「补齐」该补到哪条就说不清了——所以后一笔顶掉前一笔。
        assertEquals("88", PromptRenderer.pendingOf(history.latestPending())?.amountText)
        assertEquals(1, history.getAll().count { it.kind == "PENDING_DRAFT" && it.status == "EDITING" })
    }

    @Test
    fun `a bare amount becomes pending instead of an unnamed draft`() = runBlocking {
        val fixture = Fixture("bare-amount.db")
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                reply = "这 5 块是花在哪儿啦？",
                pending = AiPendingDraft(amountYuan = 5.0)
            ),
            input = "5", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        val pending = turn as AiTurn.Pending
        assertEquals("5", pending.draft.amountText)
        assertEquals("5", pending.draft.rawInput)
        assertTrue(pending.reply.contains("哪儿"))
    }

    @Test
    fun `the model completing a suspended amount produces a normal add`() = runBlocking {
        val fixture = Fixture("complete-pending.db")
        val carried = PendingDraft(amountText = "5", rawInput = "5", createdAt = fixture.now - 60_000)
        // 这一轮模型报了 pending 之外的正常 add —— 说明它把两句话合并了。
        val parsed = AiParseResult(
            bills = listOf(
                AiBillDraft(action = "add", amountYuan = 5.0, type = "EXPENSE",
                    category = "eating", detail = "面条")
            ),
            reply = "面条 5 块，记上啦～"
        )
        val turn = fixture.ai.toTurn(parsed, "面条", fixture.now, fixture.zone, carried)
        val drafts = (turn as AiTurn.Drafts).drafts
        assertEquals(1, drafts.size)
        assertEquals("5", drafts.single().amountText)
        assertEquals("面条", drafts.single().detail)
        assertEquals("eating", drafts.single().categoryName)
    }

    @Test
    fun `a suspended amount that is never completed keeps asking instead of guessing`() = runBlocking {
        val fixture = Fixture("still-pending.db")
        val carried = PendingDraft(amountText = "5", rawInput = "5", createdAt = fixture.now - 60_000)
        val turn = fixture.ai.toTurn(AiParseResult(reply = ""), "嗯", fixture.now, fixture.zone, carried)
        val pending = turn as AiTurn.Pending
        assertEquals("5", pending.draft.amountText)
        assertTrue("追问必须还在", pending.reply.isNotBlank())
    }

    // ---------- 改账 ----------

    @Test
    fun `an update targeting a real bill becomes a confirmable card`() = runBlocking {
        val fixture = Fixture("update-card.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 30))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 15.0, detail = "牛肉面")),
                reply = "改成 15 啦"
            ),
            input = "那碗面是 15 块", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        val commands = turn as AiTurn.Commands
        assertEquals(CommandKind.UPDATE, commands.kind)
        val item = commands.items.single()
        assertEquals(id, item.billId)
        assertEquals("15", item.newAmountText)
        assertEquals("牛肉面", item.newDetail)
        // 没提到的字段必须沿用原值，不能凭空编。
        assertEquals(1200L, fixture.billRepository.getById(id)!!.amountFen)
        assertEquals(fixture.billRepository.getById(id)!!.note, item.newNote)
        assertTrue(item.before.contains("12 元"))
        assertTrue(item.after.contains("15 元"))
    }

    @Test
    fun `an update to zero becomes a question instead of a silent wipe`() = runBlocking {
        val fixture = Fixture("update-zero.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 30))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 0.0)),
                reply = ""
            ),
            input = "那碗面改成 0", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        assertTrue("改成 0 应该反问而不是执行", turn is AiTurn.Chat)
        assertEquals(1200L, fixture.billRepository.getById(id)!!.amountFen)
    }

    @Test
    fun `an update pointing at a hallucinated id is dropped entirely`() = runBlocking {
        val fixture = Fixture("update-hallucination.db")
        fixture.bill("牛肉面", 1200, fixture.at(12, 30))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = listOf(AiBillDraft(action = "update", targetId = 999_999, amountYuan = 1.0)),
                reply = ""
            ),
            input = "把那个改了", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        assertTrue(turn is AiTurn.Chat)
        assertTrue((turn as AiTurn.Chat).reply.isNotBlank())
    }

    @Test
    fun `multiple updates in one turn all reach the card for selection`() = runBlocking {
        val fixture = Fixture("update-many.db")
        val a = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val b = fixture.bill("奶茶", 800, fixture.at(15, 0))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = listOf(
                    AiBillDraft(action = "update", targetId = a, amountYuan = 15.0),
                    AiBillDraft(action = "update", targetId = b, amountYuan = 9.0)
                ),
                reply = ""
            ),
            input = "今天吃的都涨了价", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        assertEquals(setOf(a, b), (turn as AiTurn.Commands).items.map { it.billId }.toSet())
    }

    // ---------- 删账 ----------

    @Test
    fun `a delete moves the bill to trash and it can be restored`() = runBlocking {
        val fixture = Fixture("delete-card.db")
        val id = fixture.bill("昨天的奶茶", 800, fixture.at(15, 0))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = listOf(AiBillDraft(action = "delete", targetId = id)),
                reply = "收起来了"
            ),
            input = "昨天的奶茶不要了", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        val commands = turn as AiTurn.Commands
        assertEquals(CommandKind.DELETE, commands.kind)
        assertTrue(commands.items.single().after.isEmpty())
        assertTrue(commands.items.single().before.contains("8 元"))
        // 「删掉」是软删除，账单还在，能被捞回来。
        assertNotNull(fixture.billRepository.getById(id))
        assertTrue(fixture.billRepository.observeTrash().first().isEmpty())
    }

    @Test
    fun `a delete sweep of several matched bills lists every one of them`() = runBlocking {
        val fixture = Fixture("delete-yesterday.db")
        val ids = listOf(
            fixture.bill("早饭", 500, fixture.at(-1, 8, 0)),
            fixture.bill("午饭", 1500, fixture.at(-1, 12, 0)),
            fixture.bill("奶茶", 900, fixture.at(-1, 15, 0))
        )
        // 今天这笔不该被「删掉昨天的」牵连。
        val today = fixture.bill("今天的面", 1200, fixture.at(12, 0))
        val turn = fixture.ai.toTurn(
            parsed = AiParseResult(
                bills = ids.map { AiBillDraft(action = "delete", targetId = it) },
                reply = "昨天三笔都收起来？"
            ),
            input = "把昨天的都删了", requestMillis = fixture.now, zone = fixture.zone, pending = null
        )
        val commands = turn as AiTurn.Commands
        assertEquals(ids.toSet(), commands.items.map { it.billId }.toSet())
        assertFalse(commands.items.any { it.billId == today })
    }
    // ---------- 提交与幂等 ----------

    @Test
    fun `confirming an update writes exactly the changed fields`() = runBlocking {
        val fixture = Fixture("commit-update.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 0), note = "老备注")
        fixture.bill("午饭", 100, fixture.at(13, 0))

        val turn = fixture.ai.toTurn(
            AiParseResult(
                bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 15.0)),
                reply = ""
            ),
            "面涨价了", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)
        val payload = CommandCardPayload(kind = CommandKind.UPDATE.name, items = turn.items)

        assertEquals(1, fixture.ai.commitCommands(cardId, payload))
        val updated = fixture.billRepository.getById(id)!!
        assertEquals(1500L, updated.amountFen)
        assertEquals("牛肉面", updated.detail)
        assertEquals("老备注", updated.note)
        assertEquals(fixture.at(12, 0), updated.timestamp)
    }

    @Test
    fun `confirming twice never applies the same change twice`() = runBlocking {
        val fixture = Fixture("commit-idempotent.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 15.0))),
            "涨价了", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)
        val payload = CommandCardPayload(kind = CommandKind.UPDATE.name, items = turn.items)

        assertEquals(1, fixture.ai.commitCommands(cardId, payload))
        // 重复点击：第二次没有任何东西可提交，返回 0 而不是又改一遍。
        assertEquals(0, fixture.ai.commitCommands(cardId, payload))
        assertEquals(1500L, fixture.billRepository.getById(id)!!.amountFen)
    }

    @Test
    fun `only the checked rows of a card are applied`() = runBlocking {
        val fixture = Fixture("commit-selection.db")
        val keep = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val change = fixture.bill("奶茶", 800, fixture.at(15, 0))
        val turn = fixture.ai.toTurn(
            AiParseResult(
                bills = listOf(
                    AiBillDraft(action = "update", targetId = keep, amountYuan = 15.0),
                    AiBillDraft(action = "update", targetId = change, amountYuan = 9.0)
                )
            ),
            "都涨了", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        // 用户取消了第一笔的勾选。
        val items = turn.items.map { if (it.billId == keep) it.copy(checked = false) else it }
        val cardId = fixture.storeCommandCard(turn.copy(items = items))

        assertEquals(1, fixture.ai.commitCommands(cardId, CommandCardPayload(kind = "UPDATE", items = items)))
        assertEquals(1200L, fixture.billRepository.getById(keep)!!.amountFen)
        assertEquals(900L, fixture.billRepository.getById(change)!!.amountFen)
    }

    @Test
    fun `a delete card moves every checked bill into the trash and back`() = runBlocking {
        val fixture = Fixture("commit-delete.db")
        val a = fixture.bill("早饭", 500, fixture.at(-1, 8, 0))
        val b = fixture.bill("午饭", 1500, fixture.at(-1, 12, 0))
        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "delete", targetId = a), AiBillDraft(action = "delete", targetId = b))),
            "删掉昨天的", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)

        assertEquals(2, fixture.ai.commitCommands(cardId, CommandCardPayload(kind = "DELETE", items = turn.items)))
        assertTrue(fixture.billRepository.recent(10).isEmpty())
        assertEquals(setOf(a, b), fixture.billRepository.observeTrash().first().map { it.id }.toSet())
        // 撤销 = 从回收站恢复，账目分文不动。
        assertEquals(2, fixture.billRepository.restore(listOf(a, b)))
        assertEquals(setOf(a, b), fixture.billRepository.recent(10).map { it.id }.toSet())
    }

    // ---------- 恢复（R4） ----------

    @Test
    fun `a restore targets only bills really sitting in the trash`() = runBlocking {
        val fixture = Fixture("restore-card.db")
        val id = fixture.bill("昨天的奶茶", 800, fixture.at(-1, 15, 0))
        fixture.billRepository.moveToTrash(id, fixture.now)

        val turn = fixture.ai.toTurn(
            AiParseResult(
                bills = listOf(AiBillDraft(action = "restore", targetId = id)),
                reply = "捞回来？"
            ),
            "把昨天的奶茶捞回来", fixture.now, fixture.zone, null
        ) as AiTurn.Commands

        assertEquals(CommandKind.RESTORE, turn.kind)
        val item = turn.items.single()
        assertTrue("恢复卡要标明来源是回收站：${item.before}", item.before.contains("回收站"))
        assertTrue("恢复后仍是原样（金额不动）：${item.after}", item.after.contains("8 元"))
        assertFalse("after 不该再带「回收站中」前缀", item.after.contains("回收站"))
    }

    @Test
    fun `a restore pointing at a live bill or a hallucinated id is dropped`() = runBlocking {
        val fixture = Fixture("restore-hallucination.db")
        val live = fixture.bill("活着的账", 500, fixture.at(9, 0))

        // 活账单不在回收站集合里 → 整条丢弃（与改/删同一道硬校验）。
        val onLive = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "restore", targetId = live))),
            "把刚才那笔捞回来", fixture.now, fixture.zone, null
        )
        assertTrue("活账单不能被「恢复」——应退回闲聊而不是造卡", onLive is AiTurn.Chat)

        val ghost = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "restore", targetId = 999_999L))),
            "把昨天那笔捞回来", fixture.now, fixture.zone, null
        )
        assertTrue("幻觉 id 同样丢弃", ghost is AiTurn.Chat)
    }

    @Test
    fun `confirming a restore card brings the bill back into the live ledger`() = runBlocking {
        val fixture = Fixture("restore-commit.db")
        val id = fixture.bill("昨天的奶茶", 800, fixture.at(-1, 15, 0))
        fixture.billRepository.moveToTrash(id, fixture.now)
        assertEquals(1, fixture.billRepository.observeTrash().first().size)

        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "restore", targetId = id))),
            "捞回来", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)
        val payload = CommandCardCodec.decode(fixture.history.getById(cardId)!!.draftPayload)!!

        assertEquals(1, fixture.ai.commitCommands(cardId, payload))

        assertNotNull("恢复后回到活账本", fixture.billRepository.getById(id))
        assertTrue("回收站里不再有它", fixture.billRepository.observeTrash().first().isEmpty())
    }

    @Test
    fun `restoring a bill whose category was deleted falls back to the vacuum category`() = runBlocking {
        val fixture = Fixture("restore-orphan.db")
        val id = fixture.bill("昨天的奶茶", 800, fixture.at(-1, 15, 0))
        fixture.billRepository.moveToTrash(id, fixture.now)
        // 孤儿场景：分类行被删掉，而账单（在回收站里）仍挂着那个已消失的 categoryId。
        assertEquals(1, fixture.deleteCategory(1))

        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "restore", targetId = id))),
            "捞回来", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)
        val payload = CommandCardCodec.decode(fixture.history.getById(cardId)!!.draftPayload)!!
        fixture.ai.commitCommands(cardId, payload)

        assertEquals(
            "原分类已删 → 落内置兜底分类（待定），不能留悬空外键",
            fixture.vacuumId(), fixture.billRepository.getById(id)!!.categoryId
        )
    }

    @Test
    fun `a command card survives a restart and a dismissed card rejects commits`() = runBlocking {
        val fixture = Fixture("commit-restart.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 15.0))),
            "涨价了", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)

        // 冷启动读回来的卡片，勾选与「还剩几条」都得对得上。
        val payload = CommandCardCodec.decode(fixture.history.getById(cardId)!!.draftPayload)!!
        assertEquals(1, CommandCardCodec.pendingCount(payload))
        assertEquals(CommandKind.UPDATE.name, payload.kind)
        assertEquals("15", payload.items.single().newAmountText)

        fixture.history.dismissDraft(cardId)
        assertEquals(0, fixture.ai.commitCommands(cardId, payload))
        assertEquals(1200L, fixture.billRepository.getById(id)!!.amountFen)
    }

    @Test
    fun `a card left marked as failed is still retryable`() = runBlocking {
        val fixture = Fixture("commit-failed-marker.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val turn = fixture.ai.toTurn(
            AiParseResult(bills = listOf(AiBillDraft(action = "update", targetId = id, amountYuan = 15.0))),
            "涨价了", fixture.now, fixture.zone, null
        ) as AiTurn.Commands
        val cardId = fixture.storeCommandCard(turn)
        fixture.ai.markCommandConfirmationFailed(cardId)
        val marked = CommandCardCodec.decode(fixture.history.getById(cardId)!!.draftPayload)!!
        assertTrue(marked.confirmFailed)
        // 失败记号只是给人看的，不该把卡片锁死。
        assertEquals(1, fixture.ai.commitCommands(cardId, marked))
        assertEquals(1500L, fixture.billRepository.getById(id)!!.amountFen)
    }

    @Test
    fun `every command kind has its own appended conclusion line`() {
        // R6 追加式铁律：状态变化只能靠追加 assistant 消息表达（不回改历史）。
        // 这里锁「每种动作都有一句属于自己的话」——尤其 RESTORE 不许落进「改好了」的兜底分支。
        val restore = ChatViewModel.commandResultLine(CommandKind.RESTORE, 2)
        assertFalse("恢复不能复用「改好了」的兜底文案：$restore", restore.contains("改好了"))
        assertTrue("恢复文案要带上条数：$restore", restore.contains("2"))
        assertTrue("删账文案要提回收站", ChatViewModel.commandResultLine(CommandKind.DELETE, 1).contains("回收站"))
        assertTrue("改账文案要提已更新", ChatViewModel.commandResultLine(CommandKind.UPDATE, 1).contains("改好"))
        assertTrue("0 笔时的兜底文案", ChatViewModel.commandResultLine(CommandKind.RESTORE, 0).contains("没有改动"))
    }

    // ---------- 指路 / 带路（R4 选项、R5 跳转） ----------

    @Test
    fun `asking how to restore without naming a bill yields clickable options`() = runBlocking {
        val fixture = Fixture("restore-choices.db")
        fixture.bill("昨天的奶茶", 800, fixture.at(-1, 15, 0))

        val turn = fixture.ai.toTurn(
            AiParseResult(
                reply = "想捞回来的话，阿噜可以帮你，也可以自己去回收站看看～",
                options = listOf(
                    AiOption(label = "让阿噜帮你恢复", action = "restore_assist"),
                    AiOption(label = "我自己去回收站", action = "navigate:trash")
                )
            ),
            "怎么把删掉的账找回来", fixture.now, fixture.zone, null
        ) as AiTurn.Choices

        assertEquals(2, turn.options.size)
        assertEquals(IntentActions.RESTORE_ASSIST, turn.options[0].action)
        // 「navigate:」前缀在这一层剥掉，UI 拿到的直接就是跳转目标本身。
        assertEquals(NavTargets.TRASH, turn.options[1].action)
    }

    @Test
    fun `a bare navigate instruction becomes a single option`() = runBlocking {
        val fixture = Fixture("nav-single.db")
        fixture.bill("任意一笔", 100, fixture.at(9, 0))

        val turn = fixture.ai.toTurn(
            AiParseResult(reply = "回收站在设置里，带你去～", navigate = "trash"),
            "回收站在哪", fixture.now, fixture.zone, null
        ) as AiTurn.Choices

        assertEquals(1, turn.options.size)
        assertEquals(NavTargets.TRASH, turn.options.single().action)
    }

    @Test
    fun `an option with an unknown action survives for the ui layer to ignore`() = runBlocking {
        // 表外取值（模型编的）原样保留：忽略策略在 UI——那里才知道自己认哪些页面。
        // 这里只保证「不崩、不丢」，绝不在这里替 UI 做决定。
        val fixture = Fixture("nav-unknown.db")
        fixture.bill("任意一笔", 100, fixture.at(9, 0))

        val turn = fixture.ai.toTurn(
            AiParseResult(options = listOf(AiOption(label = "凭空捏造", action = "teleport:mars"))),
            "带我去火星", fixture.now, fixture.zone, null
        ) as AiTurn.Choices

        assertEquals("teleport:mars", turn.options.single().action)
    }

    // ---------- 提示词装配 ----------

    @Test
    fun `the prompt tells the model about the suspended amount and the candidate ids`() = runBlocking {
        val fixture = Fixture("prompt-render.db")
        val id = fixture.bill("牛肉面", 1200, fixture.at(12, 0))
        val context = ChatContext(
            now = "2026-09-21 20:00（周一）", timeZone = fixture.zone.id,
            recentBills = emptyList()
        )
        val renderer = PromptRenderer(
            systemTemplate = "你是叽里咕噜",
            contextTemplate = "A={address}\nP={pending}\nC={candidates}",
            categories = listOf(CategoryEntity(id = 1, name = "eating", colorHue = 1f, colorIndex = 0)),
            context = context,
            nickname = "路陌", suffix = "大人",
            candidates = PromptRenderer.candidatesFrom(
                listOf(fixture.billEntity(id, "牛肉面", 1200, fixture.at(12, 0))), fixture.now, fixture.zone
            ),
            pending = PendingDraft("5", rawInput = "5", createdAt = fixture.now),
            zone = fixture.zone
        )
        // 这轮的输入会被 ChatIntent 判成「疑似改账」，候选段（及其 id）才会注入。
        val prompt = renderer.renderContext("那碗面改成 15 块")

        assertTrue("挂起账必须进 prompt", prompt.contains("5 元"))
        assertTrue("候选账单必须带 id", prompt.contains("[$id]"))
        assertTrue("称呼进动态段", prompt.contains("路陌大人"))
    }

    @Test
    fun `candidate groups cover today yesterday and earlier this week`() = runBlocking {
        val fixture = Fixture("candidates.db")
        val today = fixture.bill("今天的", 100, fixture.at(9, 0))
        val yesterday = fixture.bill("昨天的", 100, fixture.at(-1, 9, 0))
        val earlier = fixture.bill("前几天的", 100, fixture.at(-5, 9, 0))
        val candidates = PromptRenderer.candidatesFrom(
            fixture.billRepository.recent(200), fixture.now, fixture.zone
        )
        assertTrue(candidates.today.any { it.id == today })
        assertTrue(candidates.yesterday.any { it.id == yesterday })
        assertTrue(candidates.thisWeek.any { it.id == earlier })
    }

    /** 一个带真库的测试装置，避免每条用例重复拼装 Repository。 */
    private inner class Fixture(dbName: String) {
        val zone: ZoneId = ZoneId.systemDefault()
        private val db = build(dbName)
        val now: Long = LocalDateTime.of(2026, 9, 21, 20, 0).atZone(zone).toInstant().toEpochMilli()
        val billRepository = BillRepository(db.billDao())
        val history = ChatHistoryRepository(db)
        val ai = AiRepository(
            context, CategoryRepository(db.categoryDao()), billRepository,
            UserPrefs(context), history, CategoryAdminRepository(db)
        )

        fun billEntity(id: Long, detail: String, fen: Long, at: Long) = BillEntity(
            id = id, amountFen = fen, type = BillType.EXPENSE, categoryId = 1,
            detail = detail, timestamp = at
        )

        fun bill(detail: String, fen: Long, at: Long, note: String = ""): Long = runBlocking {
            val id = db.billDao().insert(
                BillEntity(amountFen = fen, type = BillType.EXPENSE, categoryId = 1,
                    detail = detail, note = note, timestamp = at)
            )
            id
        }

        /** [dayOffset] 相对 2026-09-21 的天偏移，[hour]/[minute] 是当天时间。 */
        fun at(hour: Int, minute: Int): Long =
            LocalDateTime.of(2026, 9, 21, hour, minute).atZone(zone).toInstant().toEpochMilli()

        fun at(dayOffset: Int, hour: Int, minute: Int): Long =
            LocalDateTime.of(2026, 9, 21, hour, minute).plusDays(dayOffset.toLong())
                .atZone(zone).toInstant().toEpochMilli()

        /** 把一张指令卡写进历史，返回它的消息 id。 */
        fun storeCommandCard(turn: AiTurn.Commands): Long = runBlocking {
            history.insert(
                ChatMessageEntity(
                    kind = "COMMAND", rawInput = "测试",
                    draftPayload = CommandCardCodec.encode(turn.kind, turn.items),
                    status = "EDITING", createdAt = now
                )
            )
        }

        /** 删掉某个分类行——模拟「分类被删、账单还躺在回收站」的孤儿场景。 */
        fun deleteCategory(id: Long): Int = runBlocking { db.categoryDao().deleteById(id) }

        /** 内置兜底分类（「待定」）的 id。 */
        fun vacuumId(): Long = runBlocking { CategoryAdminRepository(db).vacuumId() }
    }

    private fun build(name: String): AppDatabase {
        names += name
        return AppDatabase.build(context, name).also { opened += it }
    }

    private fun open(name: String): AppDatabase {
        names += name
        return AppDatabase.builder(context, name).addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build().also { opened += it }
    }

    companion object {
        private const val TEST = "5"
    }
}
