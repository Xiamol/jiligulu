package com.jiligulu.app.ui.add

import android.app.Application
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.dao.CategoryDao
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddBillCategoryPreviewTest {
    private val stores = mutableListOf<androidx.lifecycle.ViewModelStore>()
    private val opened = mutableListOf<AppDatabase>()
    @After fun close() { stores.forEach { it.clear() }; opened.forEach { it.close() }; Dispatchers.resetMain() }

    private fun model(
        importPhoto: suspend (android.net.Uri) -> String = { error("unused") },
        deletePhoto: (String) -> Unit = {},
        insertBill: (com.jiligulu.app.data.local.entity.BillEntity) -> Long = { error("A preview must not insert a bill") },
        remote: suspend (String, BillType, List<CategoryEntity>) -> AiBillDraft?
    ): AddBillViewModel {
        val cats = listOf(CategoryEntity(id = 4, name = "学习", keywords = "买书", colorHue = 100f, colorIndex = 1))
        val dao = object : CategoryDao {
            override fun observeAll() = flowOf(cats)
            override suspend fun findAllOnce() = cats
            override suspend fun count() = cats.size
            override suspend fun findByName(name: String) = cats.firstOrNull { it.name == name }
            override suspend fun insert(category: CategoryEntity): Long = error("A preview must never create a category")
            override suspend fun deleteById(id: Long): Int = error("unused")
        }
        val bills = Proxy.newProxyInstance(BillDao::class.java.classLoader, arrayOf(BillDao::class.java)) { _, method, args ->
            if (method.name == "insert") insertBill(args!![0] as com.jiligulu.app.data.local.entity.BillEntity)
            else error("A category preview must never call BillDao.${method.name}")
        } as BillDao
        val db = AppDatabase.builder(RuntimeEnvironment.getApplication(), "unopened-preview-${opened.size}.db").build()
        opened += db
        return AddBillViewModel(BillRepository(bills), CategoryRepository(dao), CategoryAdminRepository(db), remote, importPhoto, deletePhoto).also { vm ->
            stores += androidx.lifecycle.ViewModelStore().apply { put("manual", vm) }
        }
    }

    @Test fun localMatchCancelsAnEarlierRemoteResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model { _, _, _ -> delay(2000); AiBillDraft(category = "旧结果") }
        backgroundScope.launch { vm.categories.collect {} }; runCurrent()
        vm.prepareCategory("陌生商品", "", BillType.EXPENSE, true)
        advanceTimeBy(650); runCurrent()
        vm.prepareCategory("买书", "", BillType.EXPENSE, true)
        advanceTimeBy(3000); runCurrent()
        assertEquals("学习", vm.categoryPreview.value.name)
        assertEquals(4L, vm.categoryPreview.value.categoryId)
        assertFalse(vm.categoryPreview.value.resolving)
        vm.cancelCategoryPreview()
    }

    @Test fun newCategoryIsPreviewedWithoutWritingAndRepeatedTextReusesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val vm = model { _, _, _ -> calls++; AiBillDraft(category = "摄影", isNewCategory = true) }
        backgroundScope.launch { vm.categories.collect {} }; runCurrent()
        vm.prepareCategory("镜头清洁", "", BillType.EXPENSE, true)
        advanceTimeBy(650); runCurrent()
        assertEquals("摄影", vm.categoryPreview.value.name)
        assertNull(vm.categoryPreview.value.categoryId)
        assertNotNull(vm.categoryPreview.value.proposal)
        vm.prepareCategory("镜头清洁", "", BillType.EXPENSE, true)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, calls)
        vm.cancelCategoryPreview()
    }

    @Test fun leavingBeforeDebounceDoesNotSpendAnApiRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val vm = model { _, _, _ -> calls++; null }
        backgroundScope.launch { vm.categories.collect {} }; runCurrent()
        vm.prepareCategory("暂时输入", "", BillType.EXPENSE, true)
        vm.cancelCategoryPreview()
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, calls)
    }

    @Test fun abandoningAnInFlightPhotoImportCleansOnlyTheFinishedCopy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val removed = mutableListOf<String>()
        val vm = model(importPhoto = { delay(700); "/owned/import.jpg" }, deletePhoto = { removed += it }) { _, _, _ -> null }
        vm.importPhoto(android.net.Uri.parse("content://fixture/photo")); runCurrent()
        assertTrue(vm.photo.value.importing)
        vm.removePhoto()
        advanceTimeBy(700); runCurrent()
        assertEquals(listOf("/owned/import.jpg"), removed)
        assertEquals("", vm.photo.value.path)
        assertFalse(vm.photo.value.importing)
    }

    @Test fun confirmingAPhotoStoresItAndNavigationDoesNotDeleteTheCommittedCopy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val removed = mutableListOf<String>()
        var inserted: com.jiligulu.app.data.local.entity.BillEntity? = null
        val vm = model(importPhoto = { "/owned/saved.jpg" }, deletePhoto = { removed += it },
            insertBill = { inserted = it; 10L }) { _, _, _ -> null }
        vm.importPhoto(android.net.Uri.parse("content://fixture/photo")); runCurrent()
        vm.save(1800, BillType.EXPENSE, 4, "生日饭", ""); runCurrent()
        assertEquals("/owned/saved.jpg", inserted?.photoUri)
        stores.last().clear()
        assertTrue(removed.isEmpty())
    }

    @Test fun staleEnabledButtonCannotSaveNewTextWithAnOldAutomaticCategory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var inserts = 0
        val vm = model(insertBill = { inserts++; 1L }) { _, _, _ -> null }
        backgroundScope.launch { vm.categories.collect {} }; runCurrent()
        vm.prepareCategory("买书", "", BillType.EXPENSE, true)
        vm.save(1800, BillType.EXPENSE, 4, "午餐", "", autoCategorized = true); runCurrent()
        assertEquals(0, inserts)
        assertFalse(vm.saveState.value.isSaving)
        assertTrue(vm.saveState.value.error.orEmpty().contains("分类还在更新"))
        vm.save(1800, BillType.EXPENSE, 4, "买书", "", autoCategorized = true); runCurrent()
        assertEquals(1, inserts)
    }

    @Test fun sourceKeyDistinguishesDetailFromNoteEvenIfCombinedTextLooksTheSame() {
        assertNotEquals(manualCategoryInputKey("买书 · 旅行", "", BillType.EXPENSE),
            manualCategoryInputKey("买书", "旅行", BillType.EXPENSE))
    }

    @Test fun clearingBeforeTheClaimedSaveLaunchStartsCleansTheUnsavedPhoto() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val removed = mutableListOf<String>()
        var inserts = 0
        val vm = model(importPhoto = { "/owned/not-yet-saved.jpg" }, deletePhoto = { removed += it },
            insertBill = { inserts++; 1L }) { _, _, _ -> null }
        vm.importPhoto(android.net.Uri.parse("content://fixture/photo")); runCurrent()
        vm.save(1800, BillType.EXPENSE, 4, "生日饭", "")
        assertTrue(vm.saveState.value.isSaving)
        stores.last().clear()
        runCurrent()
        assertEquals(0, inserts)
        assertEquals(listOf("/owned/not-yet-saved.jpg"), removed)
    }

}
