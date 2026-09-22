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
    @Query("UPDATE bills SET amountFen = :amountFen, detail = :detail, timestamp = :timestamp WHERE id = :id")
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

    /** 某分类下的活账单数量（长按删分类的确认弹窗要显示「N 笔将移到其他」）。 */
    @Query("SELECT COUNT(*) FROM bills WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun countLiveByCategory(categoryId: Long): Int

    /**
     * 运行期删分类：把源分类下的**活账单**改挂到目标分类，返回改挂条数。
     *
     * 刻意只转活账单（`deletedAt IS NULL`）：回收站里的账单保持原 `categoryId`，
     * 等它被恢复时再由 `restoreToLive` 兜底到「其他」。这是**运行期**规则，
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
