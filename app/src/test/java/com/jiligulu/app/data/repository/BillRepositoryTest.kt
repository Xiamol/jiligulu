package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.entity.BillEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class BillRepositoryTest {
    @Test
    fun `recollecting a month flow recalculates its range`() = runTest {
        val dao = RangeTrackingDao()
        var now = millis("2026-09-30T23:59:00")
        val flow = BillRepository(dao) { now }.observeCurrentMonth()
        flow.first()
        now = millis("2026-10-01T00:01:00")
        flow.first()
        assertEquals(listOf(
            millis("2026-09-01T00:00:00") to millis("2026-10-01T00:00:00"),
            millis("2026-10-01T00:00:00") to millis("2026-11-01T00:00:00")
        ), dao.ranges)
    }

    @Test
    fun `open page moves its query across month boundary without querying every tick`() = runTest {
        val dao = RangeTrackingDao()
        var now = millis("2026-12-31T23:58:00")
        val job = backgroundScope.launch { BillRepository(dao) { now }.observeCurrentMonth().collect {} }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, dao.ranges.size)
        now = millis("2027-01-01T00:00:00")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, dao.ranges.size)
        assertEquals(millis("2027-01-01T00:00:00") to millis("2027-02-01T00:00:00"), dao.ranges.last())
        job.cancel()
    }

    @Test
    fun `month offsets handle end of January and leap year`() {
        assertEquals(millis("2024-02-01T00:00:00") to millis("2024-03-01T00:00:00"),
            monthRangeAt(millis("2024-01-31T23:00:00"), 1))
    }

    private fun millis(local: String): Long =
        LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class RangeTrackingDao : BillDao {
        val ranges = mutableListOf<Pair<Long, Long>>()
        override fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>> {
            ranges += startMillis to endMillis
            return flowOf(emptyList())
        }
        override fun observeById(id: Long): Flow<BillEntity?> = error("unused")
        override suspend fun getById(id: Long): BillEntity? = error("unused")
        override suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int = error("unused")
        override suspend fun deleteById(id: Long): Int = error("unused")
        override suspend fun insert(bill: BillEntity): Long = error("unused")
        override suspend fun delete(bill: BillEntity): Unit = error("unused")
        override fun observeAll(): Flow<List<BillEntity>> = error("unused")
    }
}
