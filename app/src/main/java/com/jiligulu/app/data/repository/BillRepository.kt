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

    /**
     * AI 改账：一次改金额/细则/时间/分类/备注。
     * 只在账单仍然存活时才生效（deletedAt IS NULL），避免改到回收站里的东西。
     * 返回是否真的改到了——被改的账可能已经进了回收站，那不是异常，是正常的「没改成」。
     */
    suspend fun updateFromAi(
        id: Long, amountFen: Long, detail: String, timestamp: Long, categoryId: Long, note: String
    ): Boolean {
        require(amountFen > 0) { "金额需要大于 0" }
        require(detail.trim().isNotEmpty()) { "请填写账单名称" }
        return billDao.updateFromAi(id, amountFen, detail.trim(), timestamp, categoryId, note) == 1
    }

    /** 真删（回收站内彻底清除）。用户侧请用 [moveToTrash]。 */
    suspend fun deleteById(id: Long) {
        check(billDao.deleteById(id) == 1) { "这条账单已不存在" }
    }

    // ---------- 回收站 ----------

    /** 移入回收站（软删除）。同上：返回是否真的移进去了。 */
    suspend fun moveToTrash(id: Long, deletedAt: Long = System.currentTimeMillis()): Boolean =
        billDao.moveToTrash(id, deletedAt) == 1

    /** 批量移入回收站，返回成功条数。 */
    suspend fun moveToTrash(ids: Collection<Long>, deletedAt: Long = System.currentTimeMillis()): Int =
        ids.count { billDao.moveToTrash(it, deletedAt) == 1 }

    suspend fun restore(id: Long) {
        check(billDao.restore(id) == 1) { "这条账单已不在回收站" }
    }

    /** 批量恢复，返回成功条数。 */
    suspend fun restore(ids: Collection<Long>): Int = ids.count { billDao.restore(it) == 1 }

    fun observeTrash(): Flow<List<BillEntity>> = billDao.observeTrash()

    suspend fun trash(): List<BillEntity> = billDao.getTrash()

    suspend fun purge(id: Long) {
        check(billDao.purge(id) == 1) { "这条账单已不在回收站" }
    }

    /** 清理过期回收站项，返回清理数量。 [retentionDays] <= 0 表示永不自动清除。 */
    suspend fun purgeExpired(retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val before = nowMillis() - retentionDays * 24L * 60L * 60L * 1000L
        return billDao.purgeExpired(before)
    }

    /** 上下文注入用：最近 [limit] 条活账单。 */
    suspend fun recent(limit: Int): List<BillEntity> = billDao.recent(limit)

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

    companion object {
        /**
         * 上下文注入时扫描的候选账单条数。
         *
         * 比实际喂给模型的（约 45 条）宽一些：候选要按今天/昨天/本周分组，
         * 只取「最近 45 条」会让某天记了 50 笔时昨天那组整个消失。
         */
        const val CANDIDATE_SCAN_LIMIT = 200
    }
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
