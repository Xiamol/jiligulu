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
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Test
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
            var now = nextMonthDay - 30_000L
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
            advanceTimeBy(30_101L)
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

    @Test fun midnightUpdatesTheDefaultMonthAndSelectionTogetherWithoutAnOldMonthFirstDay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val lastDay = LocalDate.of(2026, 12, 31).millis()
            val newYear = LocalDate.of(2027, 1, 1).millis()
            var now = newYear - 30_000L
            val vm = model(listOf(
                BillEntity(id = 71, amountFen = 900, type = BillType.EXPENSE, categoryId = 1, detail = "年末午饭", timestamp = lastDay + 1000),
                BillEntity(id = 72, amountFen = 1800, type = BillType.EXPENSE, categoryId = 1, detail = "新年午饭", timestamp = newYear + 1000)
            )) { now }
            store.put("stats", vm)
            val selectedDates = mutableListOf<Long>()
            val compactRanges = mutableListOf<StatsDateWindow>()
            backgroundScope.launch { vm.selectedDay.collect { selectedDates += it } }
            backgroundScope.launch { vm.compactWindow.collect { compactRanges += it } }
            backgroundScope.launch { vm.cashFlowBars.collect {} }
            backgroundScope.launch { vm.compactCashFlowBars.collect {} }
            backgroundScope.launch { vm.monthLabel.collect {} }
            backgroundScope.launch { vm.dayDetails.collect {} }
            runCurrent()
            assertEquals(lastDay, vm.selectedDay.value)
            assertEquals(LocalDate.of(2026, 12, 1).millis(), vm.cashFlowBars.value.first().dayStartMillis)
            assertEquals(listOf(71L), vm.dayDetails.value.map { it.id })

            now = newYear
            advanceTimeBy(30_101L); runCurrent()
            assertEquals(newYear, vm.selectedDay.value)
            assertEquals(Formatters.monthLabel(newYear), vm.monthLabel.value)
            assertEquals(newYear, vm.cashFlowBars.value.first().dayStartMillis)
            assertEquals(31, vm.cashFlowBars.value.size)
            assertEquals(LocalDate.of(2026, 12, 23).millis(), vm.compactCashFlowBars.value.first().dayStartMillis)
            assertEquals(newYear, vm.compactCashFlowBars.value.last().dayStartMillis)
            assertEquals(2700L, vm.compactCashFlowBars.value.sumOf { it.amountFen })
            assertEquals(listOf(72L), vm.dayDetails.value.map { it.id })
            assertTrue(selectedDates.all { it == lastDay || it == newYear })
            assertTrue(compactRanges.all { it == compactStatsWindow(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31)) ||
                it == compactStatsWindow(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1)) })

            val emissions = selectedDates.size to compactRanges.size
            advanceTimeBy(60_000L); runCurrent()
            assertEquals(emissions, selectedDates.size to compactRanges.size)
        } finally { store.clear() }
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
            assertEquals(1, vm.monthOffset.value)
            assertEquals(YearMonth.now().plusMonths(1).atDay(1).millis(), vm.selectedDay.value)
            assertEquals(emptyList<Long>(), vm.dayDetails.value.map { it.id })
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

    @Test fun compactWindowQueriesBothMonthsAndBarSelectionKeepsItsVisibleDates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val december = LocalDate.of(2026, 12, 31).millis()
            val january = LocalDate.of(2027, 1, 1).millis()
            val vm = model(listOf(
                BillEntity(id = 41, amountFen = 1000, type = BillType.EXPENSE, categoryId = 1, detail = "年末午饭", timestamp = december + 1000),
                BillEntity(id = 42, amountFen = 500, type = BillType.EXPENSE, categoryId = 1, detail = "新年水果", timestamp = january + 1000)
            )) { System.currentTimeMillis() }
            store.put("stats", vm)
            backgroundScope.launch { vm.compactCashFlowBars.collect {} }
            backgroundScope.launch { vm.dayDetails.collect {} }
            backgroundScope.launch { vm.cashFlowBars.collect {} }
            runCurrent()
            vm.selectCalendarDate(december); runCurrent()
            val first = LocalDate.of(2026, 12, 27).millis()
            assertEquals(first, vm.compactCashFlowBars.value.first().dayStartMillis)
            assertEquals(LocalDate.of(2027, 1, 5).millis(), vm.compactCashFlowBars.value.last().dayStartMillis)
            assertEquals(1500L, vm.compactCashFlowBars.value.sumOf { it.amountFen })
            vm.selectDay(january); runCurrent()
            assertEquals(first, vm.compactCashFlowBars.value.first().dayStartMillis)
            assertEquals(listOf(42L), vm.dayDetails.value.map { it.id })
            vm.shiftCompactWindow(10); runCurrent()
            assertEquals(LocalDate.of(2027, 1, 6).millis(), vm.compactCashFlowBars.value.first().dayStartMillis)
            assertEquals(LocalDate.of(2027, 1, 11).millis(), vm.selectedDay.value)
            vm.shiftMonth(1); runCurrent()
            assertEquals(LocalDate.of(2027, 2, 11).millis(), vm.selectedDay.value)
            assertEquals(28, vm.cashFlowBars.value.size)
            vm.showToday(); runCurrent()
            assertEquals(LocalDate.now().millis(), vm.selectedDay.value)
            assertEquals(compactStatsWindow(LocalDate.now(), LocalDate.now()).first.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                vm.compactCashFlowBars.value.first().dayStartMillis)
            vm.shiftCompactWindow(10); runCurrent()
            assertEquals(LocalDate.now().plusDays(1).millis(), vm.compactCashFlowBars.value.first().dayStartMillis)
            assertEquals(LocalDate.now().plusDays(10).millis(), vm.compactCashFlowBars.value.last().dayStartMillis)
            assertEquals(LocalDate.now().plusDays(10).millis(), vm.selectedDay.value)
        } finally { store.clear() }
    }

    @Test fun aChosenHistoricalMonthDoesNotDriftWhenTheCurrentCalendarRollsOver() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var now = System.currentTimeMillis()
            val historical = YearMonth.now().minusMonths(1).atDay(15).millis()
            val vm = model(emptyList()) { now }; store.put("stats", vm)
            backgroundScope.launch { vm.cashFlowBars.collect {} }
            backgroundScope.launch { vm.selectedDay.collect {} }
            runCurrent(); vm.selectCalendarDate(historical); runCurrent()
            val first = vm.cashFlowBars.value.first().dayStartMillis
            now = YearMonth.now().plusMonths(1).atDay(1).millis()
            advanceTimeBy(60_000L); runCurrent()
            assertEquals(historical, vm.selectedDay.value)
            assertEquals(first, vm.cashFlowBars.value.first().dayStartMillis)
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
            val vm = com.jiligulu.app.ui.home.HomeViewModel(sources.first, sources.second, StandardTestDispatcher(testScheduler)); store.put("home", vm)
            backgroundScope.launch { vm.dailyBills.collect {} }
            backgroundScope.launch { vm.uiState.collect {} }; runCurrent()
            assertEquals("21", vm.uiState.value.expenseText)
            assertEquals("200", vm.uiState.value.incomeText)
            assertEquals(2, vm.uiState.value.expenseCount)
            assertEquals(1, vm.uiState.value.incomeCount)
            assertEquals(3, vm.dailyBills.value.size)
            assertEquals(listOf(2L, 1L), com.jiligulu.app.ui.home.filterHomeBills(vm.dailyBills.value, today, 1, 2).map { it.id })
            assertEquals(listOf(3L), com.jiligulu.app.ui.home.filterHomeBills(vm.dailyBills.value, today, 2, 0).map { it.id })
            vm.selectDay(old); runCurrent(); assertEquals(listOf(4L), vm.dailyBills.value.map { it.id })
            vm.showToday(); runCurrent(); assertEquals(today, vm.selectedDay.value); assertEquals(3, vm.dailyBills.value.size)
        } finally { store.clear() }
    }

    @Test fun dragReportsCrossYearVisibleDatesWithoutReanchoringAndBarTapsKeepTheViewport() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val today = LocalDate.of(2026, 12, 31)
            val january = LocalDate.of(2027, 1, 1)
            val vm = model(listOf(BillEntity(id = 61, amountFen = 1550, type = BillType.EXPENSE,
                categoryId = 1, detail = "新年午饭", timestamp = january.millis() + 1000))) { today.millis() + 1000 }
            store.put("stats", vm)
            backgroundScope.launch { vm.prefetchedCashFlowBars.collect {} }
            backgroundScope.launch { vm.compactVisibleWindow.collect {} }
            backgroundScope.launch { vm.selectedDay.collect {} }
            backgroundScope.launch { vm.dayDetails.collect {} }
            backgroundScope.launch { vm.chartFollowsToday.collect {} }
            runCurrent()
            val revision = vm.chartAnchor.value.revision
            vm.reportCompactViewport(LocalDate.of(2026, 12, 27).millis(), LocalDate.of(2027, 1, 6).millis(), true)
            runCurrent()
            val dragged = StatsDateWindow(LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 6))
            assertEquals(dragged, vm.compactVisibleWindow.value)
            assertEquals(revision, vm.chartAnchor.value.revision)
            assertEquals(today.millis(), vm.selectedDay.value)
            assertFalse(vm.chartFollowsToday.value)
            vm.selectDay(january.millis()); runCurrent()
            assertEquals(dragged, vm.compactVisibleWindow.value)
            assertEquals(revision, vm.chartAnchor.value.revision)
            assertEquals(listOf(61L), vm.dayDetails.value.map { it.id })
            vm.showToday(); runCurrent()
            assertEquals(today.millis(), vm.selectedDay.value)
            assertEquals(compactStatsWindow(today, today), vm.compactVisibleWindow.value)
            assertTrue(vm.chartFollowsToday.value)
            assertTrue(vm.chartAnchor.value.revision > revision)
        } finally { store.clear() }
    }

    @Test fun fastDailyViewportChangesReuseSmallPrefetchQueriesInsteadOfReloadingTheLedger() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val today = LocalDate.of(2027, 1, 1)
            val queries = ArrayList<Pair<Long, Long>>()
            val vm = model(emptyList(), queries = queries) { today.millis() + 1000 }
            store.put("stats", vm)
            backgroundScope.launch { vm.prefetchedCashFlowBars.collect {} }
            backgroundScope.launch { vm.compactVisibleWindow.collect {} }
            runCurrent()
            val first = compactStatsWindow(today, today).first
            for (offset in 0 until 200) {
                vm.reportCompactViewport(first.plusDays(offset.toLong()).millis(), first.plusDays(offset + 10L).millis(), true)
                runCurrent()
            }
            assertTrue("200 date changes should reuse the buffer, queries=${queries.size}", queries.size in 2..39)
            assertTrue(queries.all { queryDays(it) in 1L..31L })
            assertEquals(first.plusDays(199), vm.compactVisibleWindow.value.first)
            assertEquals(first.plusDays(209), vm.compactVisibleWindow.value.last)
        } finally { store.clear() }
    }

    @Test fun monthViewportPrefetchesNeighboursAndPreservesThePreferredDayAcrossShortMonths() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val today = LocalDate.of(2026, 12, 31)
            val queries = ArrayList<Pair<Long, Long>>()
            val vm = model(emptyList(), queries = queries) { today.millis() + 1000 }
            store.put("stats", vm)
            backgroundScope.launch { vm.pagedMonthCashFlowBars.collect {} }
            backgroundScope.launch { vm.selectedDay.collect {} }
            backgroundScope.launch { vm.chartMonth.collect {} }
            runCurrent()
            val revision = vm.chartAnchor.value.revision
            vm.reportMonthViewport(LocalDate.of(2027, 1, 1).millis(), true); runCurrent()
            assertEquals(LocalDate.of(2027, 1, 31).millis(), vm.selectedDay.value)
            vm.reportMonthViewport(LocalDate.of(2027, 2, 1).millis(), true); runCurrent()
            assertEquals(LocalDate.of(2027, 2, 28).millis(), vm.selectedDay.value)
            vm.reportMonthViewport(LocalDate.of(2027, 3, 1).millis(), true); runCurrent()
            assertEquals(LocalDate.of(2027, 3, 31).millis(), vm.selectedDay.value)
            assertEquals(YearMonth.of(2027, 3), vm.chartMonth.value)
            assertEquals(revision, vm.chartAnchor.value.revision)
            assertTrue(queries.all { queryDays(it) in 28L..92L })
            assertEquals(LocalDate.of(2027, 2, 1).millis(), vm.pagedMonthCashFlowBars.value.first().dayStartMillis)
            assertEquals(LocalDate.of(2027, 4, 30).millis(), vm.pagedMonthCashFlowBars.value.last().dayStartMillis)
            vm.showToday(); runCurrent()
            assertEquals(today.millis(), vm.selectedDay.value)
            assertEquals(YearMonth.of(2026, 12), vm.chartMonth.value)
        } finally { store.clear() }
    }

    private fun LocalDate.millis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun queryDays(range: Pair<Long, Long>): Long = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(range.first).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(range.second).atZone(ZoneId.systemDefault()).toLocalDate())

    private fun model(bills: List<BillEntity>, categories: List<CategoryEntity> = emptyList(),
        queries: MutableList<Pair<Long, Long>>? = null, now: () -> Long): StatsViewModel {
        val sources = repositories(bills, categories, queries, now)
        return StatsViewModel(sources.first, sources.second, sources.third, nowMillis = now)
    }
    private fun repositories(bills: List<BillEntity>, categories: List<CategoryEntity> = emptyList(),
        queries: MutableList<Pair<Long, Long>>? = null, now: () -> Long): Triple<BillRepository, CategoryRepository, BudgetRepository> {
        val billDao = object : BillDao {
            override fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>> {
                queries?.add(startMillis to endMillis)
                return flowOf(bills.filter { it.timestamp >= startMillis && it.timestamp < endMillis })
            }
            override fun observeAll() = flowOf(bills)
            override fun observePhotoMemories() = flowOf(bills.filter { it.photoUri != null })
            override fun observeById(id: Long) = flowOf(bills.find { it.id == id })
            override suspend fun getById(id: Long) = bills.find { it.id == id }
            override suspend fun insert(bill: BillEntity): Long = error("Date navigation must not write bills")
            override suspend fun delete(bill: BillEntity): Unit = error("Date navigation must not write bills")
            override suspend fun deleteById(id: Long): Int = error("Date navigation must not write bills")
            override suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int =
                error("Date navigation must not write bills")
            override suspend fun updateFromAi(id: Long, amountFen: Long, detail: String, timestamp: Long, categoryId: Long, note: String): Int =
                error("Date navigation must not write bills")
            override suspend fun updateWithMemory(id: Long, amountFen: Long, detail: String, timestamp: Long, note: String, photoUri: String?): Int =
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
