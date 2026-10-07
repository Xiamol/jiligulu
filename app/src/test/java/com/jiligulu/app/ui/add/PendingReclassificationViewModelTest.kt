package com.jiligulu.app.ui.add

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.core.ai.PendingCategoryClassifier
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
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
    private val context get() = RuntimeEnvironment.getApplication<Application>()
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
    private suspend fun await(condition: () -> Boolean) = withTimeout(10000) {
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
}
