package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.domain.category.CategoryDefaults
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PendingReclassificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private fun db(): AppDatabase {
        val name = "reclassification-${names.size}.db"; names += name
        return AppDatabase.build(context, name).also { opened += it }
    }
    @After fun close() { opened.forEach { it.close() }; names.forEach(context::deleteDatabase) }

    @Test fun classificationChangesOnlyCategoryAndNeverTouchesTrashOrCopiesBills() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db); val vacuum = admin.vacuumId()
        val id = db.billDao().insert(BillEntity(amountFen = 780, type = BillType.INCOME,
            categoryId = vacuum, detail = "妈妈给的苹果钱", note = "完整备注", timestamp = 555,
            photoUri = "/keepsake/original.jpg", rawText = "最初输入"))
        val original = db.billDao().getById(id)!!
        val trashed = db.billDao().insert(original.copy(id = 0, deletedAt = 42))
        val fruit = db.categoryDao().findByName("水果")!!
        val pending = admin.pendingBills()
        assertEquals(listOf(id), pending.map { it.id })
        assertEquals(vacuum, db.billDao().getById(id)!!.categoryId)
        val result = admin.applyReclassification(listOf(CategoryReclassification(original,
            AiBillDraft(category = "水果"), fruit.id)))
        assertEquals(CategoryReclassificationResult(1, 0), result)
        assertEquals(original.copy(categoryId = fruit.id), db.billDao().getById(id))
        assertEquals(vacuum, db.billDao().getById(trashed)!!.categoryId)
        assertEquals(1, db.billDao().recent(10).size)
    }

    @Test fun editsDeletionsAndRemovedTargetsInvalidateOnlyTheAffectedPreviews() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db); val vacuum = admin.vacuumId()
        suspend fun original(detail: String): BillEntity {
            val id = db.billDao().insert(BillEntity(amountFen = 500, type = BillType.EXPENSE,
                categoryId = vacuum, detail = detail, timestamp = 100))
            return db.billDao().getById(id)!!
        }
        val edited = original("后来改动")
        val deleted = original("后来删除")
        val removedCategory = original("分类删了")
        val valid = original("待分类摄影")
        val customTarget = CategoryRepository(db.categoryDao()).createCategory("镜头保养")
        db.billDao().updateDetails(edited.id, 600, "新内容", 222)
        db.billDao().moveToTrash(deleted.id, 3)
        db.categoryDao().deleteById(customTarget)
        val proposals = listOf(edited, deleted, removedCategory, valid).map { row ->
            CategoryReclassification(row, AiBillDraft(category = if (row == removedCategory) "镜头保养" else "摄影"),
                if (row == removedCategory) customTarget else null)
        }
        val result = admin.applyReclassification(proposals)
        assertEquals(CategoryReclassificationResult(1, 3), result)
        assertEquals(600L, db.billDao().getById(edited.id)!!.amountFen)
        assertEquals(vacuum, db.billDao().getById(edited.id)!!.categoryId)
        assertNotNull(db.billDao().getById(deleted.id)!!.deletedAt)
        assertNull(db.categoryDao().findByName("镜头保养"))
        assertEquals(db.categoryDao().findByName("摄影")!!.id, db.billDao().getById(valid.id)!!.categoryId)
    }

    @Test fun duplicateConfirmationCannotReclassifyAgainOrCreateDuplicateCategories() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db)
        repeat(2) { db.billDao().insert(BillEntity(amountFen = 100, type = BillType.EXPENSE,
            categoryId = admin.vacuumId(), detail = "摄影用品", timestamp = 100)) }
        val proposals = admin.pendingBills().map { CategoryReclassification(it, AiBillDraft(category = "摄影")) }
        assertEquals(2, admin.applyReclassification(proposals + proposals).moved)
        assertEquals(0, admin.applyReclassification(proposals).moved)
        assertEquals(1, db.categoryDao().findAllOnce().count { it.name == "摄影" })
    }

    @Test fun differentlySpelledSameCategoryIsCreatedOnceAndOnlyBillOwnershipChanges() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db)
        val originals = (1..2).map { index ->
            val id = db.billDao().insert(BillEntity(amountFen = 1299, type = BillType.EXPENSE,
                categoryId = admin.vacuumId(), detail = "镜头$index", note = "原始备注", timestamp = 777,
                photoUri = "/owned/photo-$index.jpg", rawText = "保留原文"))
            db.billDao().getById(id)!!
        }
        val proposals = originals.mapIndexed { index, bill -> CategoryReclassification(bill,
            AiBillDraft(category = if (index == 0) "Photography" else " photography ",
                amountYuan = 999999.0, detail = "不能覆盖", timeExpression = "明天")) }
        assertEquals(CategoryReclassificationResult(2, 0), admin.applyReclassification(proposals))
        val category = db.categoryDao().findAllOnce().single { it.name.equals("Photography", true) }
        assertTrue(category.iconValue.isNotBlank())
        assertTrue(category.keywords.isNotBlank())
        originals.forEach { assertEquals(it.copy(categoryId = category.id), db.billDao().getById(it.id)) }
    }

    @Test fun concurrentManualCreationsUseOneTransactionalNameAndKeepNestedTransactionsSafe() = runBlocking {
        val db = db()
        val repository = CategoryRepository(db.categoryDao(), database = db)
        val ids = coroutineScope {
            listOf("Photography", " photography ", "PHOTOGRAPHY").map { name ->
                async(Dispatchers.Default) { repository.createCategory(name) }
            }.awaitAll()
        }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, db.categoryDao().findAllOnce().count { it.name.equals("Photography", true) })
        db.withTransaction { assertEquals(ids.first(), repository.createCategory("photography")) }
    }

    @Test fun upgradingOldCatalogPreservesCustomNamesIconsAndLaterPresetDeletions() = runBlocking {
        val name = "old-v6-presets.db"; names += name
        val old = AppDatabase.builder(context, name).build().also { opened += it }
        val customFruit = CategoryEntity(name = "水果", iconValue = "🍑", keywords = "我家水果", colorHue = 222f,
            colorIndex = 44, createdBy = CreatedBy.USER)
        val customId = old.categoryDao().insert(customFruit)
        old.categoryDao().insert(CategoryEntity(name = CategoryDefaults.VACUUM_NAME, deletable = false,
            colorHue = 1f, colorIndex = 0))
        old.close()
        val upgrade = AppDatabase.build(context, name).also { opened += it }
        val prefs = context.getSharedPreferences("test-preset-upgrade", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val updater = CategoryPresetUpdater(CategoryAdminRepository(upgrade), prefs)
        updater.ensure(); updater.ensure()
        assertEquals(customFruit.copy(id = customId), upgrade.categoryDao().findByName("水果"))
        assertEquals(1, upgrade.categoryDao().findAllOnce().count { it.name == "水果" })
        assertNull("Older deleted categories must stay deleted", upgrade.categoryDao().findByName("饮品"))
        assertNotNull(upgrade.categoryDao().findByName("运动"))
        val removed = upgrade.categoryDao().findByName("旅行")!!.id
        upgrade.categoryDao().deleteById(removed)
        CategoryPresetUpdater(CategoryAdminRepository(upgrade), prefs).ensure()
        assertNull("A later startup must not recreate an intentionally deleted new preset", upgrade.categoryDao().findByName("旅行"))
        prefs.edit().clear().commit()
        Unit
    }
}
