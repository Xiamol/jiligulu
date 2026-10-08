package com.jiligulu.app.ui.add

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.core.ai.PendingCategoryClassifier
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PendingReclassificationViewModelTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun close() {
        stores.forEach { it.clear() }; opened.forEach { it.close() }
        names.forEach(context::deleteDatabase); Dispatchers.resetMain()
    }
    private fun db(): AppDatabase {
        val name = "pending-vm-${names.size}.db"; names += name
        return AppDatabase.build(context, name).also { opened += it }
    }
    private fun vm(db: AppDatabase,
        remote: suspend (List<BillEntity>, List<CategoryEntity>) -> Map<Long, AiBillDraft>): AddBillViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        return AddBillViewModel(BillRepository(db.billDao()), CategoryRepository(db.categoryDao()),
            CategoryAdminRepository(db), remotePendingCategories = remote).also {
            stores += ViewModelStore().apply { put("pending", it) }
        }
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(10000) {
        while (!condition()) delay(10)
    }

    @Test fun boundedBatchesArePreviewOnlyAndOnlyTheCheckedBillChangesOnConfirmation() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db)
        repeat(31) { db.billDao().insert(BillEntity(amountFen = 1280, type = BillType.EXPENSE,
            categoryId = admin.vacuumId(), detail = "陌生物件$it", timestamp = 555 + it.toLong())) }
        val calls = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val model = vm(db) { bills, _ ->
            calls += bills.size
            if (calls.size == 1) release.await()
            bills.associate { it.id to AiBillDraft(targetId = it.id, category = "摄影", iconEmoji = "📷") }
        }
        model.preparePendingReclassification()
        await { calls.isNotEmpty() }
        assertTrue(model.reclassification.value.open); assertTrue(model.reclassification.value.loading)
        assertNull("Preparing must not create suggested categories", db.categoryDao().findByName("摄影"))
        assertEquals(31, admin.pendingBills().size)
        release.complete(Unit)
        await { !model.reclassification.value.loading }
        assertEquals(listOf(PendingCategoryClassifier.BATCH_SIZE, 1), calls)
        assertEquals(31, model.reclassification.value.proposals.size)
        assertNull(db.categoryDao().findByName("摄影"))
        val chosen = model.reclassification.value.proposals.first().original
        model.confirmPendingReclassification(setOf(chosen.id))
        await { model.reclassification.value.result != null }
        assertEquals(30, admin.pendingBills().size)
        assertEquals(chosen.copy(categoryId = db.categoryDao().findByName("摄影")!!.id), db.billDao().getById(chosen.id))
        model.confirmPendingReclassification(setOf(chosen.id))
        assertEquals(30, admin.pendingBills().size)
    }

    @Test fun closingAnInFlightPreviewCancelsFutureBatchesAndLeavesTheLedgerUntouched() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db)
        val id = db.billDao().insert(BillEntity(amountFen = 500, type = BillType.EXPENSE,
            categoryId = admin.vacuumId(), detail = "陌生物件", timestamp = 100))
        val original = db.billDao().getById(id)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val model = vm(db) { bills, _ -> entered.complete(Unit); release.await()
            bills.associate { it.id to AiBillDraft(targetId = it.id, category = "摄影") } }
        model.preparePendingReclassification(); withTimeout(10000) { entered.await() }
        model.closeReclassification(); release.complete(Unit)
        delay(30)
        assertFalse(model.reclassification.value.open)
        assertEquals(original, db.billDao().getById(id))
        assertNull(db.categoryDao().findByName("摄影"))
    }

    @Test fun allPendingBillsAppearBeforeRemoteRepliesAndOfflineNewClassCanBeConfirmed() = runBlocking {
        val db = db(); val admin = CategoryAdminRepository(db)
        db.categoryDao().deleteById(db.categoryDao().findByName("水果")!!.id)
        val fruitId = db.billDao().insert(BillEntity(amountFen = 350, type = BillType.EXPENSE,
            categoryId = admin.vacuumId(), detail = "苹果", timestamp = 120, rawText = "原话", photoUri = "/fruit.jpg"))
        val unknownId = db.billDao().insert(BillEntity(amountFen = 570, type = BillType.EXPENSE,
            categoryId = admin.vacuumId(), detail = "不清楚的东西", timestamp = 130))
        val before = db.billDao().getById(fruitId)!!
        val release = CompletableDeferred<Unit>()
        val model = vm(db) { _, _ -> release.await(); error("offline") }
        model.preparePendingReclassification()
        await { model.reclassification.value.bills.size == 2 }
        assertTrue(model.reclassification.value.loading)
        assertEquals(setOf(fruitId, unknownId), model.reclassification.value.bills.map { it.id }.toSet())
        assertEquals(listOf(fruitId), model.reclassification.value.proposals.map { it.original.id })
        assertNull(db.categoryDao().findByName("水果"))
        release.complete(Unit)
        await { !model.reclassification.value.loading }
        model.confirmPendingReclassification(setOf(fruitId, unknownId))
        await { model.reclassification.value.result != null }
        val fruit = db.categoryDao().findByName("水果")!!
        assertEquals("builtin_fruit", fruit.iconValue)
        assertTrue(fruit.keywords.contains("苹果"))
        assertEquals(before.copy(categoryId = fruit.id), db.billDao().getById(fruitId))
        assertEquals(listOf(unknownId), admin.pendingBills().map { it.id })
    }

    @Test fun manualAutomaticPreviewReallyCreatesItsMissingClassOnlyWhenSaving() = runBlocking {
        val db = db()
        val repository = CategoryRepository(db.categoryDao(), database = db)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val model = AddBillViewModel(BillRepository(db.billDao()), repository, CategoryAdminRepository(db))
        stores += ViewModelStore().apply { put("manual", model) }
        model.prepareCategory("镜头清洁", "留存", BillType.EXPENSE, true)
        val preview = model.categoryPreview.value
        assertEquals("摄影", preview.name)
        assertNull(preview.categoryId)
        assertNotNull(preview.proposal)
        assertNull(db.categoryDao().findByName("摄影"))
        model.save(1280, BillType.EXPENSE, -1, "镜头清洁", "留存", timestamp = 678,
            proposedCategory = preview.proposal, autoCategorized = true)
        await { db.billDao().recent(10).isNotEmpty() }
        val category = db.categoryDao().findByName("摄影")!!
        assertEquals("📷", category.iconValue)
        assertTrue(category.keywords.contains("镜头"))
        val bill = db.billDao().recent(10).single()
        assertEquals(category.id, bill.categoryId)
        assertEquals(1280L, bill.amountFen)
        assertEquals(678L, bill.timestamp)
        assertEquals("镜头清洁", bill.detail)
        assertEquals("留存", bill.note)
    }
}
