package com.jiligulu.app.data.repository

import androidx.room.withTransaction
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.domain.category.CategoryDefaults

/** 删除分类的结果。用显式类型而不是布尔，让「被拒绝」与「真失败」在调用侧可分。 */
sealed interface CategoryDeletionResult {
    /** 删除成功；[reassigned] 是被改挂到「其他」的活账单条数。 */
    data class Deleted(val reassigned: Int) : CategoryDeletionResult

    /** 被拒绝（例如目标就是收纳箱「其他」本身）；此时数据库**未发生任何改动**。 */
    data class Refused(val reason: String) : CategoryDeletionResult
}

/**
 * 分类管理：删除分类并把其活账单原子地转挂到内置「其他」。
 *
 * 之所以单独成仓库，而不是塞进 [CategoryRepository]：
 * 这是一次跨 `categories` + `bills` 两表的写，必须走 [AppDatabase.withTransaction]；
 * 而 [CategoryRepository] 只持有 `CategoryDao`，且现有多处测试以假 DAO 构造它，
 * 把 DB 塞进它的构造签名会污染一大批测试。
 *
 * @param beforeCategoryDelete 失败注入点，仅供测试在「转归属」与「删行」之间抛异常，
 *   以验证事务整体回滚。生产路径恒为空实现。
 */
class CategoryAdminRepository(
    private val database: AppDatabase,
    private val beforeCategoryDelete: suspend () -> Unit = {}
) {
    private val categoryDao get() = database.categoryDao()
    private val billDao get() = database.billDao()

    /**
     * 解析内置「其他」分类的 id。
     *
     * 只认 [CategoryDefaults.VACUUM_NAME]，不硬编码字符串；拿不到说明数据库未完成 v6 迁移。
     */
    suspend fun vacuumId(): Long =
        categoryDao.findByName(CategoryDefaults.VACUUM_NAME)?.id
            ?: error("内置「其他」分类不存在，数据库可能未完成迁移")

    /**
     * 删除 [categoryId] 并把它的**活账单**改挂到「其他」，全程一个事务。
     *
     * 规则与边界：
     * - 只转**活账单**（`deletedAt IS NULL`）；回收站账单保持原 `categoryId`，
     *   等被恢复时再兜底到「其他」（见 §4.1 运行期规则）。
     * - [categoryId] 指向收纳箱「其他」本身（`deletable = false`）→ 拒绝，库不动。
     * - 任何一步抛错 → 整个事务回滚，分类与账单归属都不变。
     */
    suspend fun deleteCategoryAndReassign(categoryId: Long): CategoryDeletionResult =
        database.withTransaction {
            val target = categoryDao.findAllOnce().firstOrNull { it.id == categoryId }
                ?: return@withTransaction CategoryDeletionResult.Refused("这条分类已经不在了")
            if (!target.deletable) {
                return@withTransaction CategoryDeletionResult.Refused("「${target.name}」是收纳箱，删不得哦")
            }
            val vacuum = categoryDao.findByName(CategoryDefaults.VACUUM_NAME)?.id
                ?: error("内置「其他」分类不存在，数据库可能未完成迁移")
            // 即便「其他」因数据异常被标成可删，也不允许把收纳箱自己删掉。
            check(vacuum != categoryId) { "「其他」是收纳箱，删不得哦" }

            val reassigned = billDao.reassignCategory(categoryId, vacuum)
            beforeCategoryDelete()
            check(categoryDao.deleteById(categoryId) == 1) { "这条分类已经不在了" }
            CategoryDeletionResult.Deleted(reassigned)
        }
}
