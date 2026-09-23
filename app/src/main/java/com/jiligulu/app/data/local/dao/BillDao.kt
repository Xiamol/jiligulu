package com.jiligulu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.jiligulu.app.data.local.entity.BillEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账单表。
 *
 * **软删除约定**：`deletedAt IS NULL` 才是「活着的」账单。
 * 凡是对外提供数据的查询（首页/统计/预算/日历/导出）都必须带这个条件；
 * 只有回收站自己的查询反过来用 `deletedAt IS NOT NULL`。
 */
@Dao
interface BillDao {

    @Insert
    suspend fun insert(bill: BillEntity): Long

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: Long): Flow<BillEntity?>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    /** Only user-editable columns change; attachments and original AI input remain intact. */
    @Query("UPDATE bills SET amountFen = :amountFen, detail = :detail, timestamp = :timestamp WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int

    /** AI 改账用：可同时改分类，且只在账单存活时生效。 */
    @Query(
        """
        UPDATE bills SET amountFen = :amountFen, detail = :detail, timestamp = :timestamp,
            categoryId = :categoryId, note = :note
        WHERE id = :id AND deletedAt IS NULL
        """
    )
    suspend fun updateFromAi(
        id: Long, amountFen: Long, detail: String, timestamp: Long, categoryId: Long, note: String
    ): Int

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 时间范围内的账单，新的在前。日视图/月视图/预算周期都靠它，聚合在内存里做 */
    @Query(
        "SELECT * FROM bills WHERE deletedAt IS NULL AND timestamp >= :startMillis AND timestamp < :endMillis " +
            "ORDER BY timestamp DESC"
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE deletedAt IS NULL ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query(
        "SELECT * FROM bills WHERE deletedAt IS NULL AND timestamp >= :startMillis " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentSince(startMillis: Long, limit: Int): List<BillEntity>

    /** 上下文注入用：最近若干条活着的账单，新的在前。 */
    @Query("SELECT * FROM bills WHERE deletedAt IS NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BillEntity>

    // ---------- 分类删除 / 恢复（v0.6） ----------

    /** 某分类下的活账单数量（长按删分类的确认弹窗要显示「N 笔将移到待定」）。 */
    @Query("SELECT COUNT(*) FROM bills WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun countLiveByCategory(categoryId: Long): Int

    /**
     * 运行期删分类：把源分类下的**活账单**改挂到目标分类，返回改挂条数。
     *
     * 刻意只转活账单（`deletedAt IS NULL`）：回收站里的账单保持原 `categoryId`，
     * 等它被恢复时再由 `restoreToLive` 兜底到「待定」。这是**运行期**规则，
     * 与**迁移期**（重复分类整体消失，回收站账单也一并换挂点）不同。
     */
    @Query("UPDATE bills SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId AND deletedAt IS NULL")
    suspend fun reassignCategory(fromCategoryId: Long, toCategoryId: Long): Int

    /** AI「恢复账单」的候选：回收站内按删除时间倒序，最近删的在前。 */
    @Query("SELECT * FROM bills WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC LIMIT :limit")
    suspend fun trashCandidates(limit: Int): List<BillEntity>

    // ---------- 回收站 ----------

    /** 移入回收站（软删除）。已在回收站里的不会被重复打时间戳。 */
    @Query("UPDATE bills SET deletedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL")
    suspend fun moveToTrash(id: Long, deletedAt: Long): Int

    /** 从回收站恢复。 */
    @Query("UPDATE bills SET deletedAt = NULL WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restore(id: Long): Int

    /**
     * R4 AI 恢复：把回收站里的账捞回活账本（deletedAt 置空）。
     * 与 [restore] 语义相同、独立成方法，是因为调用方还要紧跟一步孤儿分类兜底
     * （见 [reassignCategoryIfOrphan]），两步在仓库层成对出现，分开命名方便测试桩按需覆写。
     */
    @Query("UPDATE bills SET deletedAt = NULL WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restoreToLive(id: Long): Int

    /**
     * 恢复后兜底：账单的 categoryId 指向已不存在的分类时，改挂到 [fallbackCategoryId]（内置「待定」）。
     *
     * 为什么会有孤儿分类：运行期删分类只把**活账单**转挂「待定」（见 [reassignCategory]），
     * 回收站里的账单保持原 categoryId——等它被恢复时再由本查询兜底，避免指向不存在的分类。
     * 子查询只匹配「分类表里没有的行」，活分类下的账单不受影响。
     */
    @Query(
        "UPDATE bills SET categoryId = :fallbackCategoryId " +
            "WHERE id = :id AND categoryId NOT IN (SELECT id FROM categories)"
    )
    suspend fun reassignCategoryIfOrphan(id: Long, fallbackCategoryId: Long): Int

    /** 回收站列表：按删除时间倒序，最新删的在最上面。 */
    @Query("SELECT * FROM bills WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getTrash(): List<BillEntity>

    /** 彻底删除（仅回收站内的账单，避免误伤活账单）。 */
    @Query("DELETE FROM bills WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun purge(id: Long): Int

    /** 清理超过保留期的回收站账单，返回清掉的数量。 */
    @Query("DELETE FROM bills WHERE deletedAt IS NOT NULL AND deletedAt < :beforeMillis")
    suspend fun purgeExpired(beforeMillis: Long): Int
}
