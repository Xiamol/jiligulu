package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.util.Calendar

class BillRepository(
    private val billDao: BillDao,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    /** Resolve the range on each subscription and refresh it while the page spans a month boundary. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentMonth(): Flow<List<BillEntity>> = observeMonthRange()
        .flatMapLatest { (start, end) -> billDao.observeBetween(start, end) }

    fun observeMonthRange(offset: Int = 0): Flow<Pair<Long, Long>> = flow {
        while (true) {
            emit(monthRangeAt(nowMillis(), offset))
            delay(60_000L)
        }
    }.distinctUntilChanged()

    fun observeById(id: Long): Flow<BillEntity?> = billDao.observeById(id)

    suspend fun getById(id: Long): BillEntity? = billDao.getById(id)

    suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long) {
        require(amountFen > 0) { "金额需要大于 0" }
        require(detail.trim().isNotEmpty()) { "请填写账单名称" }
        check(billDao.updateDetails(id, amountFen, detail.trim(), timestamp) == 1) { "这条账单已不存在" }
    }

    suspend fun deleteById(id: Long) {
        check(billDao.deleteById(id) == 1) { "这条账单已不存在" }
    }

    /** 任意时间范围账单流（M3 统计页月导航用） */
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>> =
        billDao.observeBetween(startMillis, endMillis)

    suspend fun addManual(
        amountFen: Long,
        type: BillType,
        categoryId: Long,
        detail: String,
        note: String,
        timestamp: Long = System.currentTimeMillis()
    ): Long = billDao.insert(
        BillEntity(
            amountFen = amountFen,
            type = type,
            categoryId = categoryId,
            detail = detail.trim(),
            note = note.trim(),
            timestamp = timestamp,
            source = BillSource.MANUAL
        )
    )

    /** AI 对话记账入库（保留 AI 原始输入，便于回溯/调试 prompt） */
    suspend fun addFromAi(
        amountFen: Long,
        type: BillType,
        categoryId: Long,
        detail: String,
        note: String,
        rawText: String,
        timestamp: Long = System.currentTimeMillis()
    ): Long = billDao.insert(
        BillEntity(
            amountFen = amountFen,
            type = type,
            categoryId = categoryId,
            detail = detail.trim(),
            note = note.trim(),
            timestamp = timestamp,
            source = BillSource.AI_CHAT,
            rawText = rawText
        )
    )

    suspend fun delete(bill: BillEntity) = billDao.delete(bill)
}

internal fun monthRangeAt(timestamp: Long, offset: Int = 0): Pair<Long, Long> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = calendar.timeInMillis
    calendar.add(Calendar.MONTH, 1)
    return start to calendar.timeInMillis
}
