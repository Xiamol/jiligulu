package com.jiligulu.app.ui.trash

import android.app.Application
import android.content.Context
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回收站双页签（账单 / 草稿）的行为验收。
 *
 * 重点锁两件容易写错的事：
 * 1. **两个页签的勾选互不串**——共用一个 `selected` 字段的话，在草稿页点「恢复」
 *    会去恢复一笔根本没勾的账单，而界面还显示着「已选 1 张草稿」，用户完全看不懂；
 * 2. **删草稿 = 打标记**（不是物理删），并**追加一条消息**让聊天流如实反映
 *    （R6 追加式：状态变化只追加、永不回改历史）。
 *
 * ⚠️ 这里必须 `Dispatchers.setMain(Dispatchers.Unconfined)`：
 * ViewModel 在 init 里用 `viewModelScope` 收 Flow，而 Robolectric 环境不会自动推进
 * Main looper——不换掉 Main dispatcher 的话，collect 永远不跑，`first {}` 会挂死
 * （实测卡了 6 分钟，最后靠 kill 才停）。所有等待都套 `withTimeout` 兜底。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TrashTabsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun db(name: String): AppDatabase {
        names += name
        return AppDatabase.build(context, name).also { opened += it }
    }

    private fun model(db: AppDatabase) = TrashViewModel(
        BillRepository(db.billDao()),
        CategoryRepository(db.categoryDao()),
        UserPrefs(context),
        ChatHistoryRepository(db)
    )

    private suspend fun trashedBill(db: AppDatabase, detail: String): Long = db.billDao().insert(
        BillEntity(
            amountFen = 1200, type = BillType.EXPENSE, categoryId = 1,
            detail = detail, timestamp = 1_000, deletedAt = 2_000
        )
    )

    private suspend fun activeDraft(db: AppDatabase, raw: String): Long = ChatHistoryRepository(db).insert(
        ChatMessageEntity(
            kind = "DRAFT", status = "EDITING", rawInput = raw, createdAt = 1_000,
            draftPayload = DraftHistoryCodec.encode(
                listOf(DraftUi(amountText = "10", detail = "奶茶", categoryName = "饮品"))
            )
        )
    )

    /** 等状态满足条件；超时就抛错，绝不静默挂死。 */
    private suspend fun TrashViewModel.await(timeoutMs: Long = 8_000, predicate: (TrashUiState) -> Boolean) {
        withTimeout(timeoutMs) { uiState.first(predicate) }
    }

    private fun TrashUiState.dump() = "tab=$tab bills=${items.size} drafts=${draftItems.size} " +
        "selected=$selected selectedDrafts=$selectedDrafts"

    @Test
    fun `selections stay separate per tab`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = db("trash-tabs.db")
        val billId = trashedBill(db, "牛肉面")
        val draftId = activeDraft(db, "10块奶茶")
        val vm = model(db)
        vm.await { it.items.size == 1 && it.draftItems.size == 1 }

        vm.toggle(billId)
        assertEquals("账单页勾选应生效", setOf(billId), vm.uiState.value.activeSelection)

        vm.selectTab(TrashTab.DRAFTS)
        assertTrue("切到草稿页不该继承账单页的勾选", vm.uiState.value.activeSelection.isEmpty())

        vm.toggle(draftId)
        assertEquals(setOf(draftId), vm.uiState.value.activeSelection)

        vm.selectTab(TrashTab.BILLS)
        assertEquals("切回账单页，原来的勾选还在", setOf(billId), vm.uiState.value.activeSelection)
    }

    @Test
    fun `deleting selected drafts marks them deleted and appends a message`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = db("trash-drafts.db")
        val draftId = activeDraft(db, "10块奶茶")
        val history = ChatHistoryRepository(db)
        val vm = model(db)
        vm.await { it.draftItems.size == 1 }

        vm.selectTab(TrashTab.DRAFTS)
        vm.toggle(draftId)
        vm.deleteSelectedDrafts()
        vm.await { !it.isWorking && it.message != null }

        assertEquals("删草稿是打 DELETED 标记，不物理删", "DELETED", history.getById(draftId)!!.status)
        assertTrue("草稿列表里不该再有它（${vm.uiState.value.dump()}）", vm.uiState.value.draftItems.isEmpty())
        val appended = history.getAll().lastOrNull { it.kind == "ASSISTANT" }
        assertTrue(
            "必须追加一条消息告知（R6 追加式）：${appended?.content}",
            appended?.content?.contains("1 张草稿") == true
        )
    }

    @Test
    fun `the draft tab never shows already deleted drafts`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = db("trash-drafts-filter.db")
        val kept = activeDraft(db, "留下的草稿")
        val gone = activeDraft(db, "已经删掉的草稿")
        ChatHistoryRepository(db).deleteDraft(gone)

        val vm = model(db)
        vm.await { it.draftItems.isNotEmpty() }

        assertEquals("只剩一张活跃草稿", 1, vm.uiState.value.draftItems.size)
        assertEquals(kept, vm.uiState.value.draftItems.single().id)
    }

    @Test
    fun `manual restore moves orphan categories into catchall and preserves existing categories`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = db("trash-restore-orphan.db")
        val fallback = db.categoryDao().findAllOnce().first { !it.deletable }.id
        val liveCategory = db.categoryDao().findAllOnce().first { it.deletable }.id
        val removedCategory = db.categoryDao().insert(CategoryEntity(name = "旧分类", colorHue = 100f, colorIndex = 9))
        val orphan = db.billDao().insert(BillEntity(amountFen = 1234, type = BillType.EXPENSE,
            categoryId = removedCategory, detail = "保持账单内容", timestamp = 1_000, deletedAt = 2_000))
        val existing = db.billDao().insert(BillEntity(amountFen = 500, type = BillType.EXPENSE,
            categoryId = liveCategory, detail = "原分类仍在", timestamp = 1_000, deletedAt = 2_000))
        db.categoryDao().deleteById(removedCategory)
        val vm = model(db)
        vm.await { it.items.size == 2 }
        vm.toggle(orphan)
        vm.toggle(existing)
        vm.restoreSelected()
        vm.await { !it.isWorking && it.message != null }
        assertEquals(fallback, db.billDao().getById(orphan)!!.categoryId)
        assertEquals(liveCategory, db.billDao().getById(existing)!!.categoryId)
        assertEquals(null, db.billDao().getById(orphan)!!.deletedAt)
        assertEquals(1234L, db.billDao().getById(orphan)!!.amountFen)
    }

    @Test
    fun `an operation on the bill tab never touches drafts`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = db("trash-cross-tab.db")
        val billId = trashedBill(db, "牛肉面")
        val draftId = activeDraft(db, "10块奶茶")
        val vm = model(db)
        vm.await { it.items.size == 1 && it.draftItems.size == 1 }

        // 只在账单页勾了东西，却切到草稿页点「删掉草稿」——应当被拦下，而不是误删草稿
        vm.toggle(billId)
        vm.selectTab(TrashTab.DRAFTS)
        vm.deleteSelectedDrafts()
        vm.await { it.error != null }

        assertEquals("草稿必须原样保留", "EDITING", ChatHistoryRepository(db).getById(draftId)!!.status)
        assertFalse("账单也不该被顺手清掉", vm.uiState.value.items.isEmpty())
    }
}
