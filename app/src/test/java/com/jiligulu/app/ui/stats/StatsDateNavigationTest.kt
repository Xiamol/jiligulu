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

    private fun LocalDate.millis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun model(bills: List<BillEntity>, now: () -> Long): StatsViewModel {
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
        }
        val categoryDao = object : CategoryDao {
            override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(emptyList())
            override suspend fun count(): Int = 0
            override suspend fun findByName(name: String): CategoryEntity? = null
            override suspend fun insert(category: CategoryEntity): Long = error("unused")
        }
        val budgetDao = object : BudgetDao {
            override fun observe(): Flow<BudgetEntity?> = flowOf(null)
            override suspend fun upsert(budget: BudgetEntity): Unit = error("unused")
            override suspend fun clear(): Unit = error("unused")
        }
        return StatsViewModel(BillRepository(billDao, now), CategoryRepository(categoryDao),
            BudgetRepository(budgetDao, billDao))
    }
}
