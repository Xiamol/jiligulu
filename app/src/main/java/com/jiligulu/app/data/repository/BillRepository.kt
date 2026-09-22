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

    /**
     * AI「恢复账单」（R4）：把回收站里的账捞回活账本，返回是否真的恢复了。
     *
     * 比 [restore] 多做一步孤儿分类兜底：运行期删分类只转挂**活账单**，回收站账单保留的
     * categoryId 可能已指向不存在的分类，恢复后由 DAO 子查询改挂到 [fallbackCategoryId]
     * （内置「待定」）。两步不包在同一个事务里——与 [restore] / [moveToTrash] 的批量实现一致，
     * 中途失败的最坏结果是一笔分类显示为「未分类」的账，不会损坏数据。
     */
    suspend fun restoreToLive(id: Long, fallbackCategoryId: Long): Boolean {
        if (billDao.restoreToLive(id) != 1) return false
        billDao.reassignCategoryIfOrphan(id, fallbackCategoryId)
        return true
    }

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

    /** 某分类下的活账单数量（长按删分类的确认弹窗用）。 */
    suspend fun countLiveByCategory(id: Long): Int = billDao.countLiveByCategory(id)

    /** AI「恢复账单」候选：回收站内最近删的 [limit] 条。 */
    suspend fun trashCandidates(limit: Int): List<BillEntity> = billDao.trashCandidates(limit)

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

        /**
         * R4 恢复候选：回收站内取最近删的多少条进 prompt。
         *
         * 与活账候选的入 prompt 量（约 45）保持一致；回收站通常远少于这个数，
         * 且只在用户提到「恢复」时才注入（见 ChatIntent.needsTrash），不额外烧 token。
         */
        const val TRASH_CANDIDATE_LIMIT = 45
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
