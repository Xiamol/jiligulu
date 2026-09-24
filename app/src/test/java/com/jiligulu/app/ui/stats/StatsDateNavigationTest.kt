package com.jiligulu.app.ui.stats

import androidx.lifecycle.ViewModelStore
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.dao.BudgetDao
import com.jiligulu.app.data.local.dao.CategoryDao
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.BudgetRepository
import com.jiligulu.app.data.repository.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsDateNavigationTest {
    @After
    fun restoreMainDispatcher() {
        // runTest must finish draining ViewModel and collector cancellation before Main is restored.
        Dispatchers.resetMain()
    }

    @Test
    fun `month rollover moves selected date and details into new month`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val nextMonthDay = YearMonth.now().plusMonths(1).atDay(1).millis()
            var now = System.currentTimeMillis()
            val bill = BillEntity(id = 1, amountFen = 900, type = BillType.EXPENSE, categoryId = 1,
                detail = "跨月午饭", timestamp = nextMonthDay + 12 * 60 * 60 * 1000L)
            val oldBill = BillEntity(id = 3, amountFen = 500, type = BillType.EXPENSE, categoryId = 2,
                detail = "旧月份饮品", timestamp = now)
            val vm = model(listOf(bill, oldBill)) { now }
            store.put("stats", vm)
            backgroundScope.launch { vm.selectedDay.collect {} }
            backgroundScope.launch { vm.dayDetails.collect {} }
            backgroundScope.launch { vm.monthLabel.collect {} }
            runCurrent()
            assertEquals(Formatters.dayStart(now), vm.selectedDay.value)
            vm.toggleCategory(2)
            runCurrent()
            assertEquals(listOf(3L), vm.dayDetails.value.map { it.id })
            now = nextMonthDay
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(0, vm.monthOffset.value)
            assertEquals(nextMonthDay, vm.selectedDay.value)
            assertEquals(Formatters.monthLabel(nextMonthDay), vm.monthLabel.value)
            assertEquals(null, vm.selectedCategoryId.value)
            assertEquals(listOf(1L), vm.dayDetails.value.map { it.id })
        } finally {
            store.clear()
        }
    }

    @Test
    fun `aggregate slice never shares a name with a real category`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val now = System.currentTimeMillis()
            val names = listOf("其他", "其余", "其余（合并）", "其余（合并） 2", "吃饭", "饮品", "交通", "购物")
            val cats = names.mapIndexed { i, name -> CategoryEntity(id = i + 1L, name = name,
                colorHue = i * 40f, colorIndex = i) }
            val bills = cats.mapIndexed { i, cat -> BillEntity(id = i + 1L, amountFen = (1000 - i * 10).toLong(),
                type = BillType.EXPENSE, categoryId = cat.id, detail = cat.name, timestamp = now) }
            val vm = model(bills, cats) { now }
            store.put("stats", vm)
            backgroundScope.launch { vm.dayDonut.collect {} }
            runCurrent()
            val slices = vm.dayDonut.value.slices
            assertEquals(7, slices.size)
            assertEquals(slices.size, slices.map { it.label }.toSet().size)
            assertEquals("其余（合并） 3", slices.last().label)
            assertTrue(slices.any { it.label == "其他" })
            backgroundScope.launch { vm.dayDetails.collect {} }
            vm.toggleCategory(-1L); runCurrent()
            assertEquals(listOf(7L, 8L), vm.dayDetails.value.map { it.id }.sorted())
        } finally { store.clear() }
    }

    @Test
    fun `calendar selects another month and its details without editing a bill`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val targetDay = YearMonth.now().minusMonths(1).atDay(15).millis()
            val bill = BillEntity(id = 2, amountFen = 1200, type = BillType.EXPENSE, categoryId = 1,
                detail = "上月补记", timestamp = targetDay + 12 * 60 * 60 * 1000L)
            val vm = model(listOf(bill)) { System.currentTimeMillis() }
            store.put("stats", vm)
            backgroundScope.launch { vm.selectedDay.collect {} }
            backgroundScope.launch { vm.dayDetails.collect {} }
            runCurrent()
            vm.selectCalendarDate(targetDay)
            runCurrent()
            assertEquals(-1, vm.monthOffset.value)
            assertEquals(targetDay, vm.selectedDay.value)
            assertEquals(listOf(2L), vm.dayDetails.value.map { it.id })
            vm.selectCalendarDate(YearMonth.now().plusMonths(1).atDay(1).millis())
            runCurrent()
            assertEquals(-1, vm.monthOffset.value)
            assertEquals(targetDay, vm.selectedDay.value)
        } finally {
            store.clear()
        }
    }

    @Test fun incomeExpenseAndSwipedDaysShareOneFilter() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val today = LocalDate.now().millis()
            val yesterday = LocalDate.now().minusDays(1).millis()
            val bills = listOf(
                BillEntity(id = 1, amountFen = 900, type = BillType.EXPENSE, categoryId = 1, detail = "午饭", timestamp = today + 1000),
                BillEntity(id = 2, amountFen = 20000, type = BillType.INCOME, categoryId = 2, detail = "转账", timestamp = today + 2000),
                BillEntity(id = 3, amountFen = 30000, type = BillType.INCOME, categoryId = 2, detail = "昨天转账", timestamp = yesterday + 2000))
            val vm = model(bills) { System.currentTimeMillis() }; store.put("stats", vm)
            backgroundScope.launch { vm.dayDonut.collect {} }; backgroundScope.launch { vm.dayDetails.collect {} }; backgroundScope.launch { vm.cashFlowBars.collect {} }
            runCurrent()
            assertEquals("9", vm.dayDonut.value.totalText)
            vm.toggleCategory(1); runCurrent(); vm.setFlowType(BillType.INCOME); runCurrent()
            assertEquals(null, vm.selectedCategoryId.value)
            assertEquals("200", vm.dayDonut.value.totalText)
            assertEquals(listOf(2L), vm.dayDetails.value.map { it.id })
            vm.shiftDay(-1); runCurrent()
            assertEquals(yesterday, vm.selectedDay.value)
            assertEquals("300", vm.dayDonut.value.totalText)
            assertEquals(listOf(3L), vm.dayDetails.value.map { it.id })
        } finally { store.clear() }
    }

    @Test fun homeQueriesOtherMonthsAndResetsTodayWithCompactFilters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val today = LocalDate.now().millis()
            val old = YearMonth.now().minusMonths(1).atDay(15).millis()
            val bills = listOf(
                BillEntity(id = 1, amountFen = 900, type = BillType.EXPENSE, categoryId = 1, detail = "午饭", timestamp = today + 1000),
                BillEntity(id = 2, amountFen = 1200, type = BillType.EXPENSE, categoryId = 1, detail = "晚饭", timestamp = today + 2000),
                BillEntity(id = 3, amountFen = 20000, type = BillType.INCOME, categoryId = 2, detail = "收入", timestamp = today + 3000),
                BillEntity(id = 4, amountFen = 300, type = BillType.EXPENSE, categoryId = 1, detail = "旧账", timestamp = old + 1000))
            val sources = repositories(bills) { System.currentTimeMillis() }
            val vm = com.jiligulu.app.ui.home.HomeViewModel(sources.first, sources.second); store.put("home", vm)
            backgroundScope.launch { vm.dailyBills.collect {} }; runCurrent()
            assertEquals(3, vm.dailyBills.value.size)
            assertEquals(listOf(2L, 1L), com.jiligulu.app.ui.home.filterHomeBills(vm.dailyBills.value, today, 1, 2).map { it.id })
            assertEquals(listOf(3L), com.jiligulu.app.ui.home.filterHomeBills(vm.dailyBills.value, today, 2, 0).map { it.id })
            vm.selectDay(old); runCurrent(); assertEquals(listOf(4L), vm.dailyBills.value.map { it.id })
            vm.showToday(); runCurrent(); assertEquals(today, vm.selectedDay.value); assertEquals(3, vm.dailyBills.value.size)
        } finally { store.clear() }
    }

    private fun LocalDate.millis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun model(bills: List<BillEntity>, categories: List<CategoryEntity> = emptyList(), now: () -> Long): StatsViewModel {
        val sources = repositories(bills, categories, now)
        return StatsViewModel(sources.first, sources.second, sources.third)
    }
    private fun repositories(bills: List<BillEntity>, categories: List<CategoryEntity> = emptyList(), now: () -> Long): Triple<BillRepository, CategoryRepository, BudgetRepository> {
        val billDao = object : BillDao {
            override fun observeBetween(startMillis: Long, endMillis: Long) = flowOf(
                bills.filter { it.timestamp >= startMillis && it.timestamp < endMillis })
            override fun observeAll() = flowOf(bills)
            override fun observeById(id: Long) = flowOf(bills.find { it.id == id })
            override suspend fun getById(id: Long) = bills.find { it.id == id }
            override suspend fun insert(bill: BillEntity): Long = error("Date navigation must not write bills")
            override suspend fun delete(bill: BillEntity): Unit = error("Date navigation must not write bills")
            override suspend fun deleteById(id: Long): Int = error("Date navigation must not write bills")
            override suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int =
                error("Date navigation must not write bills")
            override suspend fun updateFromAi(id: Long, amountFen: Long, detail: String, timestamp: Long, categoryId: Long, note: String): Int =
                error("Date navigation must not write bills")
            override suspend fun recentSince(startMillis: Long, limit: Int): List<BillEntity> = error("unused")
            override suspend fun recent(limit: Int): List<BillEntity> = error("unused")
            override suspend fun countLiveByCategory(categoryId: Long): Int = error("unused")
            override suspend fun reassignCategory(fromCategoryId: Long, toCategoryId: Long): Int = error("unused")
            override suspend fun trashCandidates(limit: Int): List<BillEntity> = error("unused")
            override suspend fun moveToTrash(id: Long, deletedAt: Long): Int = error("unused")
            override suspend fun restore(id: Long): Int = error("unused")
            override suspend fun restoreToLive(id: Long): Int = error("unused")
            override suspend fun reassignCategoryIfOrphan(id: Long, fallbackCategoryId: Long): Int = error("unused")
            override fun observeTrash(): Flow<List<BillEntity>> = error("unused")
            override suspend fun getTrash(): List<BillEntity> = error("unused")
            override suspend fun purge(id: Long): Int = error("unused")
            override suspend fun purgeExpired(beforeMillis: Long): Int = error("unused")
        }
        val categoryDao = object : CategoryDao {
            override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(categories)
            override suspend fun findAllOnce(): List<CategoryEntity> = categories
            override suspend fun count(): Int = 0
            override suspend fun findByName(name: String): CategoryEntity? = null
            override suspend fun insert(category: CategoryEntity): Long = error("unused")
            override suspend fun deleteById(id: Long): Int = error("unused")
        }
        val budgetDao = object : BudgetDao {
            override fun observe(): Flow<BudgetEntity?> = flowOf(null)
            override suspend fun upsert(budget: BudgetEntity): Unit = error("unused")
            override suspend fun clear(): Unit = error("unused")
        }
        return Triple(BillRepository(billDao, now), CategoryRepository(categoryDao),
            BudgetRepository(budgetDao, billDao))
    }
}
