package com.jiligulu.app.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.repository.ConfirmItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Real SQLite databases reconstructed from the shipped Room schemas, then opened by Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DatabaseUpgradeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test fun version1PreservesBillsAndCategories() = verifyUpgrade(1)
    @Test fun version2PreservesBillsAndSvgCategories() = verifyUpgrade(2)
    @Test fun version3PreservesBillsCategoriesAndBudget() = verifyUpgrade(3)
    @Test fun version4PreservesLedgerAndStartsWithAnEmptyTrash() = verifyUpgrade(4)

    private fun verifyUpgrade(version: Int) = runBlocking {
        val name = "migration-$version.db"
        createHistoricalDatabase(name, version)
        val db = open(name)
        // These DAO calls force Room's real migration and schema validation.
        assertEquals(listOf(sampleBill), db.billDao().observeAll().first())
        val categories = db.categoryDao().observeAll().first()
        // 迁移保留历史样本分类原样，并补齐内置收纳箱「待定」(v6)。
        assertEquals(2, categories.size)
        assertEquals(sampleCategory.copy(iconSvg = if (version == 1) "" else SVG),
            categories.single { it.name == "自定义午餐" })
        val vacuum = categories.single { it.name == "待定" }
        assertFalse("收纳箱「待定」不可删", vacuum.deletable)
        assertEquals(if (version >= 3) sampleBudget else null, db.budgetDao().observe().first())
        assertEquals(if (version >= 4) 1 else 0, db.chatMessageDao().getAll().size)
        assertEquals(AppDatabase.SCHEMA_VERSION, db.openHelper.readableDatabase.version)
        // v5 之前没有软删除列，历史账单迁移后一律视为「活着的」，回收站是空的。
        assertTrue(db.billDao().getTrash().isEmpty())
        assertNull(db.billDao().getById(sampleBill.id)?.deletedAt)

        val addedBill = db.billDao().insert(sampleBill.copy(id = 0, detail = "升级后新账单"))
        assertTrue(addedBill > sampleBill.id)
        val newCategory = db.categoryDao().insert(sampleCategory.copy(id = 0, name = "升级后分类"))
        assertTrue(newCategory > sampleCategory.id)
        db.close()

        val reopened = open(name)
        assertEquals(2, reopened.billDao().observeAll().first().size)
        assertEquals(3, reopened.categoryDao().count())
        assertEquals(sampleBill, reopened.billDao().observeAll().first().single { it.id == sampleBill.id })
        assertEquals(if (version >= 3) sampleBudget else null, reopened.budgetDao().observe().first())
    }

    @Test
    fun firstOpenSeedsAreReadyAndReopeningDoesNotDuplicateThem() = runBlocking {
        names += "fresh.db"
        val db = AppDatabase.build(context, "fresh.db")
        opened += db
        assertEquals(listOf("吃饭", "饮品", "待定"), db.categoryDao().observeAll().first().map { it.name })
        db.close()
        val reopened = AppDatabase.build(context, "fresh.db")
        opened += reopened
        assertEquals(3, reopened.categoryDao().count())
    }

    /**
     * v0.6 回收站：软删除的账单必须从所有用户可见视图消失，但仍在回收站里等着被捞回来。
     * 用真 SQLite 跑，因为「deletedAt IS NULL」写错一个字，只有真库会告诉你。
     */
    @Test
    fun trashedBillsLeaveEveryVisibleViewButStayRecoverable() = runBlocking {
        names += "trash.db"
        val db = AppDatabase.build(context, "trash.db")
        opened += db
        val bills = BillRepository(db.billDao())
        val kept = bills.addManual(1200, BillType.EXPENSE, 1, "留下的面", "")
        val doomed = bills.addManual(3000, BillType.EXPENSE, 1, "要删的火锅", "")
        val dayStart = millis(2020, 1, 1)
        val dayEnd = millis(2030, 1, 1)

        assertEquals(2, db.billDao().observeBetween(dayStart, dayEnd).first().size)
        assertEquals(1, db.billDao().moveToTrash(doomed, 1_000L))
        // 首页/统计/导出走的都是这几个查询——一处漏过滤就会让删掉的账单诈尸。
        assertEquals(listOf(kept), db.billDao().observeBetween(dayStart, dayEnd).first().map { it.id })
        assertEquals(listOf(kept), db.billDao().observeAll().first().map { it.id })
        assertEquals(listOf(kept), db.billDao().recent(10).map { it.id })
        assertEquals(listOf(doomed), bills.observeTrash().first().map { it.id })
        assertNotNull(db.billDao().getById(doomed)?.deletedAt)

        // 已在回收站里的不会被重复盖章，第二条同样的删除请求返回 0。
        assertEquals(0, db.billDao().moveToTrash(doomed, 2_000L))
        // 改账不该改到回收站里的东西。
        assertEquals(0, db.billDao().updateFromAi(doomed, 1, "改不动", 0, 1, ""))

        assertEquals(1, db.billDao().restore(doomed))
        assertEquals(2, db.billDao().observeAll().first().size)
        assertTrue(bills.observeTrash().first().isEmpty())
    }

    @Test
    fun expiredTrashIsPurgedWhileRecentDeletionsAndLiveBillsSurvive() = runBlocking {
        names += "trash-expiry.db"
        val db = AppDatabase.build(context, "trash-expiry.db")
        opened += db
        val now = millis(2026, 9, 21)
        val bills = BillRepository(db.billDao()) { now }
        val live = bills.addManual(500, BillType.EXPENSE, 1, "活的", "")
        val stale = bills.addManual(600, BillType.EXPENSE, 1, "删很久了", "")
        val fresh = bills.addManual(700, BillType.EXPENSE, 1, "刚删的", "")
        db.billDao().moveToTrash(stale, now - 40L * 86_400_000L)
        db.billDao().moveToTrash(fresh, now - 2L * 86_400_000L)

        assertEquals(1, bills.purgeExpired(UserPrefs.DEFAULT_TRASH_RETENTION_DAYS))
        assertEquals(listOf(fresh), bills.observeTrash().first().map { it.id })
        assertEquals(listOf(live), db.billDao().observeAll().first().map { it.id })
        // 0 = 永不自动清除，此时一行都不许动。
        assertEquals(0, bills.purgeExpired(UserPrefs.TRASH_RETENTION_FOREVER))
        assertEquals(1, bills.observeTrash().first().size)
        // 彻底删除只作用于回收站内的账单，活账单不会因为 id 撞上就被顺手清掉。
        assertEquals(0, db.billDao().purge(live))
        assertEquals(1, db.billDao().observeAll().first().size)
    }

    @Test
    fun messagesAndEditedDraftSurviveDatabaseAndRepositoryRecreation() = runBlocking {
        val name = "chat-history.db"
        val db = open(name)
        val repo = ChatHistoryRepository(db)
        val user = ChatMessageEntity(kind = "USER", content = "昨天中午吃饭花了9块", createdAt = 123)
        val userId = repo.insert(user)
        val draft = ChatMessageEntity(kind = "DRAFT", rawInput = user.content, draftPayload = "original", status = "EDITING", createdAt = 124)
        val draftId = repo.insert(draft)
        assertEquals(1, repo.updateDraft(draftId, "edited amount and timestamp"))
        val pendingId = repo.insert(ChatMessageEntity(kind = "ASSISTANT", status = "PENDING", createdAt = 125))
        db.close()

        val restored = ChatHistoryRepository(open(name))
        assertEquals(listOf(userId, draftId, pendingId), restored.observeAll().first().map { it.id })
        assertEquals(user.copy(id = userId), restored.getById(userId))
        assertEquals(draft.copy(id = draftId, draftPayload = "edited amount and timestamp"), restored.getById(draftId))
        assertEquals(1, restored.markPendingInterrupted())
        assertEquals("INTERRUPTED", restored.getById(pendingId)?.status)
        assertFalse(restored.getById(pendingId)?.content.isNullOrBlank())
        assertEquals(0, restored.markPendingInterrupted())
    }

    @Test
    fun requestPairIsDurableTogetherAndCanBeRecoveredAfterRestart() = runBlocking {
        val db = open("pending-request.db")
        val repo = ChatHistoryRepository(db)
        val (user, pending) = repo.beginRequest("昨天午饭9元", 12345)
        assertEquals(user.id + 1, pending.id)
        assertEquals("USER", user.kind)
        assertEquals("昨天午饭9元", user.content)
        assertEquals("PENDING", pending.status)
        assertEquals(user.content, pending.rawInput)
        assertEquals(user.createdAt, pending.createdAt)
        db.close()
        val restored = ChatHistoryRepository(open("pending-request.db"))
        assertEquals(listOf(user, pending), restored.getAll())
        assertEquals(1, restored.markPendingInterrupted())
        assertEquals(user, restored.getById(user.id))
        assertEquals("INTERRUPTED", restored.getById(pending.id)?.status)
    }

    @Test
    fun failedBatchRollsBackBillsCategoryAndDraftState() = runBlocking {
        val db = open("rollback.db")
        val repo = ChatHistoryRepository(db)
        val id = repo.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING", draftPayload = "keep edits"))
        try {
            repo.confirmDraftAtomically(id, finalPayload = "freeze final timestamps") {
                val categoryId = db.categoryDao().insert(sampleCategory.copy(id = 0, name = "临时分类"))
                db.billDao().insert(sampleBill.copy(id = 0, categoryId = categoryId))
                // The second write fails inside SQLite after the first bill/category were written.
                db.billDao().insert(sampleBill.copy(id = 400, categoryId = categoryId))
                db.billDao().insert(sampleBill.copy(id = 400, categoryId = categoryId))
                3
            }
            fail("Duplicate primary key must abort the transaction")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // Expected SQLite error, not a mocked transaction or a SQL-string assertion.
        }
        assertTrue(db.billDao().observeAll().first().isEmpty())
        assertNull(db.categoryDao().findByName("临时分类"))
        assertEquals("EDITING", repo.getById(id)?.status)
        assertEquals("keep edits", repo.getById(id)?.draftPayload)
        assertEquals(0, repo.getById(id)?.savedCount)
    }

    @Test
    fun concurrentAndRepeatedConfirmationSavesOnlyOnce() = runBlocking {
        val db = open("confirm-once.db")
        val first = ChatHistoryRepository(db)
        val second = ChatHistoryRepository(db)
        val id = first.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING"))
        val callbacks = AtomicInteger()
        val results = listOf(first, second).map { repository ->
            async(Dispatchers.Default) {
                repository.confirmDraftAtomically(id, finalPayload = "confirmed payload") {
                    callbacks.incrementAndGet()
                    db.billDao().insert(sampleBill.copy(id = 0))
                    db.billDao().insert(sampleBill.copy(id = 0, detail = "第二笔"))
                    2
                }
            }
        }.awaitAll()
        assertEquals(listOf(2, 2), results)
        assertEquals(1, callbacks.get())
        assertEquals(2, db.billDao().observeAll().first().size)
        assertEquals("CONFIRMED", first.getById(id)?.status)
        assertEquals(2, first.getById(id)?.savedCount)
        assertEquals("confirmed payload", first.getById(id)?.draftPayload)
        assertEquals(0, first.updateDraft(id, "late stale edits"))
        assertEquals(0, first.dismissDraft(id))
        db.close()

        val reopened = ChatHistoryRepository(open("confirm-once.db"))
        assertEquals(2, reopened.confirmDraftAtomically(id, finalPayload = "stale payload") { error("Must not save twice after restart") })
        assertEquals("confirmed payload", reopened.getById(id)?.draftPayload)
    }

    @Test(timeout = 30_000)
    fun aiConfirmationSharesTransactionAndKeepsExplicitTime() = runBlocking {
        // 走真实生产构建器：默认种子「吃饭 / 饮品 / 待定」会在建库时写入。
        names += "ai-confirm.db"
        val db = AppDatabase.build(context, "ai-confirm.db")
        opened += db
        val history = ChatHistoryRepository(db)
        val ai = AiRepository(context, CategoryRepository(db.categoryDao()),
            BillRepository(db.billDao()), UserPrefs(context), history, CategoryAdminRepository(db))
        val id = history.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING"))
        val explicitTime = millis(2026, 8, 31)
        val draft = ConfirmItem(amountText = "9.00", type = BillType.EXPENSE,
            categoryName = "深夜食堂", isNewCategory = true, iconEmoji = "🍜", iconSvg = "",
            keywords = "夜宵", detail = "面条", note = "补记", checked = true, timestamp = explicitTime)
        // Exercises CategoryRepository's flow read and category creation within the transaction too.
        assertEquals(2, ai.confirm(id, listOf(draft, draft.copy(detail = "饺子")), "昨天吃了两餐"))
        assertEquals(2, ai.confirm(id, listOf(draft), "duplicate tap"))
        val bills = db.billDao().observeAll().first()
        assertEquals(2, bills.size)
        assertEquals(setOf(explicitTime), bills.map { it.timestamp }.toSet())
        assertEquals(setOf("昨天吃了两餐"), bills.map { it.rawText }.toSet())
        assertEquals(setOf(BillSource.AI_CHAT), bills.map { it.source }.toSet())
        val category = db.categoryDao().findByName("深夜食堂")!!
        assertEquals(setOf(category.id), bills.map { it.categoryId }.toSet())
        // 默认种子「吃饭 / 饮品 / 待定」+ AI 新建的「深夜食堂」。种子由真实生产构建器种下。
        assertEquals(4, db.categoryDao().count())
        assertEquals("CONFIRMED", history.getById(id)?.status)
    }

    @Test
    fun deletedDraftCannotBeConfirmedOrEdited() = runBlocking {
        val repo = ChatHistoryRepository(open("deleted.db"))
        val id = repo.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING"))
        assertEquals(1, repo.deleteDraft(id))
        assertEquals(0, repo.updateDraft(id, "stale edits"))
        var callbackInvoked = false
        try {
            repo.confirmDraftAtomically(id) { callbackInvoked = true; 1 }
            fail("Deleted draft must reject confirmation")
        } catch (_: IllegalStateException) { }
        assertFalse(callbackInvoked)
        assertEquals("DELETED", repo.getById(id)?.status)
    }

    @Test
    fun editingAnUpgradedBillPreservesOtherFieldsAndChangesMonthQueries() = runBlocking {
        val name = "edit-upgraded.db"
        createHistoricalDatabase(name, 3)
        val db = open(name)
        val september = millis(2026, 9, 1)
        val october = millis(2026, 10, 1)
        val november = millis(2026, 11, 1)
        assertEquals(listOf(sampleBill), db.billDao().observeBetween(september, october).first())
        assertEquals(1, db.billDao().updateDetails(sampleBill.id, 990, "补记午餐", october))
        assertTrue(db.billDao().observeBetween(september, october).first().isEmpty())
        assertEquals(listOf(sampleBill.copy(amountFen = 990, detail = "补记午餐", timestamp = october)),
            db.billDao().observeBetween(october, november).first())
    }

    @Test
    fun unsupportedDowngradeFailsWithoutErasingTheLedger() = runBlocking {
        val name = "future-version.db"
        names += name
        val db = AppDatabase.build(context, name)
        opened += db
        db.billDao().insert(sampleBill)
        db.close()
        val future = AppDatabase.SCHEMA_VERSION + 1
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = future
        }
        try {
            openWithMigrations(name).billDao().observeAll().first()
            fail("Missing migration must not silently recreate tables")
        } catch (_: IllegalStateException) { }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT detail, amountFen FROM bills WHERE id = 42", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(sampleBill.detail, cursor.getString(0))
                assertEquals(sampleBill.amountFen, cursor.getLong(1))
            }
            assertEquals(future, it.version)
        }
    }

    /**
     * 根因断言：不带迁移时 Room 会认为「schema 对不上，重建吧」。
     * 这个测试锁的是 [AppDatabase.builder] 的行为，和生产路径的迁移链互补。
     */
    @Test
    fun bareBuilderReportsTheSchemaMismatchInsteadOfRebuildingSilently() = runBlocking {
        val name = "bare-builder.db"
        names += name
        val future = AppDatabase.SCHEMA_VERSION + 1
        val created = AppDatabase.build(context, name)
        created.billDao().insert(sampleBill)
        created.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = future
        }
        try {
            openBare(name).billDao().observeAll().first()
            fail("No migration registered means Room must refuse to open a future schema")
        } catch (_: IllegalStateException) { }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT detail FROM bills WHERE id = 42", null).use { cursor ->
                assertTrue("Ledger must survive a refused open", cursor.moveToFirst())
                assertEquals(sampleBill.detail, cursor.getString(0))
            }
            assertEquals(future, it.version)
        }
    }

    private fun open(name: String): AppDatabase {
        names += name
        return openWithMigrations(name).also { opened += it }
    }

    /** 装齐迁移链，用来断言「历史库能否被真实迁移并验证 schema」。 */
    private fun openWithMigrations(name: String): AppDatabase =
        AppDatabase.builder(context, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()

    /** 只装 schema（无迁移、无种子），断言根因时用。 */
    private fun openBare(name: String): AppDatabase =
        AppDatabase.builder(context, name).build()

    private fun createHistoricalDatabase(name: String, version: Int) {
        names += name
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val resource = javaClass.classLoader!!.getResourceAsStream("com.jiligulu.app.data.local.AppDatabase/$version.json")
        assertNotNull("Historical Room schema v$version must be packaged as a test resource", resource)
        val schema = JSONObject(resource!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            val svgColumn = if (version >= 2) ", iconSvg" else ""
            val svgPlaceholder = if (version >= 2) ", ?" else ""
            val categoryValues = mutableListOf<Any>(sampleCategory.id, sampleCategory.name, sampleCategory.iconType.name,
                sampleCategory.iconValue, sampleCategory.colorHue, sampleCategory.colorIndex,
                sampleCategory.createdBy.name, sampleCategory.keywords)
            if (version >= 2) categoryValues += SVG
            db.execSQL("INSERT INTO categories (id, name, iconType, iconValue, colorHue, colorIndex, createdBy, keywords$svgColumn) VALUES (?, ?, ?, ?, ?, ?, ?, ?$svgPlaceholder)", categoryValues.toTypedArray())
            db.execSQL("INSERT INTO bills (id, amountFen, type, categoryId, detail, note, photoUri, timestamp, source, rawText) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(sampleBill.id, sampleBill.amountFen, sampleBill.type.name, sampleBill.categoryId,
                    sampleBill.detail, sampleBill.note, sampleBill.photoUri, sampleBill.timestamp,
                    sampleBill.source.name, sampleBill.rawText))
            if (version >= 3) db.execSQL("INSERT INTO budgets (id, amountFen, periodType, anchorDay, updatedAt) VALUES (?, ?, ?, ?, ?)",
                arrayOf(sampleBudget.id, sampleBudget.amountFen, sampleBudget.periodType.name, sampleBudget.anchorDay, sampleBudget.updatedAt))
            if (version >= 4) db.execSQL("INSERT INTO chat_messages (id, kind, content, rawInput, draftPayload, status, savedCount, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(1L, "USER", "历史对话", "", "", "", 0, millis(2026, 9, 30)))
            db.version = version
        }
    }

    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val sampleCategory = CategoryEntity(id = 7, name = "自定义午餐", iconType = IconType.SVG,
        iconValue = "🍜", iconSvg = SVG, colorHue = 137.5f, colorIndex = 9, createdBy = CreatedBy.USER, keywords = "面,饭")
    private val sampleBill = BillEntity(id = 42, amountFen = 12345, type = BillType.EXPENSE,
        categoryId = 7, detail = "历史午餐", note = "旧版手写备注", photoUri = "content://local/receipt/42",
        timestamp = millis(2026, 9, 30), source = BillSource.AI_CHAT, rawText = "原始记账描述")
    private val sampleBudget = BudgetEntity(amountFen = 987654, periodType = BudgetPeriod.MONTHLY,
        anchorDay = 15, updatedAt = 1777777000000)

    companion object {
        private const val SVG = "<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"8\"/></svg>"
    }
}
