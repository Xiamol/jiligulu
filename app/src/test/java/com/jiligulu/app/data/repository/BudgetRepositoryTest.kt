package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.dao.BudgetDao
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.domain.budget.BudgetEngine
import com.jiligulu.app.domain.budget.BudgetStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetRepositoryTest {
    @Test fun dailyQueryFollowsMidnight() = checkRollover(BudgetPeriod.DAILY)
    @Test fun weeklyQueryFollowsAnchor() = checkRollover(BudgetPeriod.WEEKLY)
    @Test fun monthlyQueryFollowsNewMonth() = checkRollover(BudgetPeriod.MONTHLY)

    private fun checkRollover(period: BudgetPeriod) = runTest {
        var now = millis("2026-09-30T23:58:00")
        val before = now
        val after = millis("2026-10-01T00:01:00")
        val budget = BudgetEntity(amountFen = 10_000, periodType = period,
            updatedAt = millis("2026-09-24T10:00:00"))
        val billDao = RangeDao()
        billDao.bills.value = listOf(bill(1, before, 900))
        val dao = object : BudgetDao {
            override fun observe(): Flow<BudgetEntity?> = flowOf(budget)
            override suspend fun upsert(budget: BudgetEntity): Unit = error("unused")
            override suspend fun clear(): Unit = error("unused")
        }
        var status: BudgetStatus? = null
        backgroundScope.launch { BudgetRepository(dao, billDao) { now }.observeStatus().collect { status = it } }
        runCurrent()
        assertEquals(900L, status!!.spentFen)
        advanceTimeBy(60_000L); runCurrent()
        assertEquals(1, billDao.ranges.size) // No new database query on an unchanged timer tick.
        now = after
        billDao.bills.value += bill(2, after, 500)
        advanceTimeBy(60_000L); runCurrent()
        assertEquals(2, billDao.ranges.size)
        assertEquals(BudgetEngine.currentPeriod(budget, after), billDao.ranges.last())
        assertEquals(500L, status!!.spentFen)
        billDao.bills.value += bill(3, after + 1000, 200)
        runCurrent()
        assertEquals(700L, status!!.spentFen)
    }

    @Test fun dailyPeriodUsesLocalMidnightAcrossDaylightSaving() = withNewYork {
        val budget = BudgetEntity(amountFen = 10000, periodType = BudgetPeriod.DAILY)
        assertEquals(millis("2026-03-08T00:00:00") to millis("2026-03-09T00:00:00"),
            BudgetEngine.currentPeriod(budget, millis("2026-03-08T12:00:00")))
        assertEquals(millis("2026-11-01T00:00:00") to millis("2026-11-02T00:00:00"),
            BudgetEngine.currentPeriod(budget, millis("2026-11-01T12:00:00")))
    }

    @Test fun weeklyPeriodDoesNotStartAnHourLateAfterClockChange() = withNewYork {
        val budget = BudgetEntity(amountFen = 10000, periodType = BudgetPeriod.WEEKLY,
            updatedAt = millis("2026-03-02T09:00:00"))
        assertEquals(millis("2026-03-09T00:00:00") to millis("2026-03-16T00:00:00"),
            BudgetEngine.currentPeriod(budget, millis("2026-03-09T00:01:00")))
    }

    private fun withNewYork(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); block() }
        finally { TimeZone.setDefault(original) }
    }

    private fun millis(local: String): Long =
        LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun bill(id: Long, at: Long, amount: Long) =
        BillEntity(id = id, amountFen = amount, type = BillType.EXPENSE, categoryId = 1, timestamp = at)

    private class RangeDao : BillDao {
        val ranges = mutableListOf<Pair<Long, Long>>()
        val bills = MutableStateFlow(emptyList<BillEntity>())
        override fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>> {
            ranges += startMillis to endMillis
            return bills.map { entries -> entries.filter { it.timestamp in startMillis until endMillis } }
        }
        override fun observeById(id: Long): Flow<BillEntity?> = error("unused")
        override suspend fun getById(id: Long): BillEntity? = error("unused")
        override suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int = error("unused")
        override suspend fun updateFromAi(id: Long, amountFen: Long, detail: String, timestamp: Long, categoryId: Long, note: String): Int = error("unused")
        override suspend fun deleteById(id: Long): Int = error("unused")
        override suspend fun insert(bill: BillEntity): Long = error("unused")
        override suspend fun delete(bill: BillEntity): Unit = error("unused")
        override fun observeAll(): Flow<List<BillEntity>> = error("unused")
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
}
