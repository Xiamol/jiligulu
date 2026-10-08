package com.jiligulu.app.data.repository

import androidx.room.withTransaction
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.domain.category.CategorySuggestions
import com.jiligulu.app.data.local.entity.IconType

/** 删除分类的结果。用显式类型而不是布尔，让「被拒绝」与「真失败」在调用侧可分。 */
sealed interface CategoryDeletionResult {
    /** 删除成功；[reassigned] 是被改挂到「待定」的活账单条数。 */
    data class Deleted(val reassigned: Int) : CategoryDeletionResult

    /** 被拒绝（例如目标就是收纳箱「待定」本身）；此时数据库**未发生任何改动**。 */
    data class Refused(val reason: String) : CategoryDeletionResult
}

/**
 * 分类管理：删除分类并把其活账单原子地转挂到内置「待定」。
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
     * 解析内置「待定」分类的 id。
     *
     * 只认 [CategoryDefaults.VACUUM_NAME]，不硬编码字符串；拿不到说明数据库未完成 v6 迁移。
     */
    suspend fun vacuumId(): Long =
        categoryDao.findByName(CategoryDefaults.VACUUM_NAME)?.id
            ?: error("内置「待定」分类不存在，数据库可能未完成迁移")

    suspend fun pendingBills(): List<com.jiligulu.app.data.local.entity.BillEntity> {
        val vacuum = vacuumId()
        return database.ledgerLookupDao().details(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT * FROM bills WHERE deletedAt IS NULL AND categoryId = ? ORDER BY timestamp DESC, id DESC", arrayOf(vacuum)))
    }

    /** The only writable column is categoryId; a stale preview must never overwrite an edited bill. */
    suspend fun applyReclassification(proposals: List<CategoryReclassification>): CategoryReclassificationResult =
        database.withTransaction {
            val vacuum = vacuumId()
            var moved = 0
            var skipped = 0
            proposals.distinctBy { it.original.id }.forEach { proposal ->
                val original = proposal.original
                val current = billDao.getById(original.id)
                if (current == null || current != original || current.deletedAt != null || current.categoryId != vacuum) {
                    skipped++; return@forEach
                }
                val name = CategorySuggestions.name(proposal.suggestion.category)
                if (name == null || name == CategoryDefaults.VACUUM_NAME) {
                    skipped++; return@forEach
                }
                val target = CategorySuggestions.existing(name, categoryDao.findAllOnce())
                if (proposal.targetCategoryId != null && (target?.id != proposal.targetCategoryId || target?.deletable != true)) {
                    skipped++; return@forEach
                }
                if (target != null && !target.deletable) { skipped++; return@forEach }
                val targetId = target?.id ?: run {
                    val index = categoryDao.count()
                    val icon = CategorySuggestions.icon(name, proposal.suggestion.iconEmoji)
                    categoryDao.insert(com.jiligulu.app.data.local.entity.CategoryEntity(
                        name = name, iconValue = icon, iconType = if (icon.startsWith("builtin_")) IconType.BUILTIN else IconType.EMOJI,
                        keywords = CategorySuggestions.keywords(name, proposal.suggestion.keywords),
                        colorIndex = index, colorHue = com.jiligulu.app.domain.color.GoldenAnglePalette.hueFor(index),
                        createdBy = com.jiligulu.app.data.local.entity.CreatedBy.AI))
                }
                // This guarded update changes no amount/date/details/attachment/raw input.
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE bills SET categoryId = ? WHERE id = ? AND categoryId = ? AND deletedAt IS NULL",
                    arrayOf(targetId, original.id, vacuum))
                moved++
            }
            CategoryReclassificationResult(moved, skipped)
        }

    /** Only newly introduced presets are seeded on upgrade; older user deletions stay deleted. */
    suspend fun addSupplementalPresets() = database.withTransaction {
        CategoryDefaults.supplementalPresets.forEach { seed ->
            if (categoryDao.findByName(seed.name) == null) {
                val index = categoryDao.count()
                categoryDao.insert(com.jiligulu.app.data.local.entity.CategoryEntity(name = seed.name,
                    iconType = com.jiligulu.app.data.local.entity.IconType.BUILTIN, iconValue = seed.icon,
                    keywords = seed.keywords, createdBy = com.jiligulu.app.data.local.entity.CreatedBy.DEFAULT,
                    colorIndex = index, colorHue = com.jiligulu.app.domain.color.GoldenAnglePalette.hueFor(index)))
            }
        }
    }

    /**
     * 删除 [categoryId] 并把它的**活账单**改挂到「待定」，全程一个事务。
     *
     * 规则与边界：
     * - 只转**活账单**（`deletedAt IS NULL`）；回收站账单保持原 `categoryId`，
     *   等被恢复时再兜底到「待定」（见 §4.1 运行期规则）。
     * - [categoryId] 指向收纳箱「待定」本身（`deletable = false`）→ 拒绝，库不动。
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
                ?: error("内置「待定」分类不存在，数据库可能未完成迁移")
            // 即便「待定」因数据异常被标成可删，也不允许把收纳箱自己删掉。
            check(vacuum != categoryId) { "「待定」是收纳箱，删不得哦" }

            val reassigned = billDao.reassignCategory(categoryId, vacuum)
            beforeCategoryDelete()
            check(categoryDao.deleteById(categoryId) == 1) { "这条分类已经不在了" }
            CategoryDeletionResult.Deleted(reassigned)
        }
}

data class CategoryReclassification(
    val original: com.jiligulu.app.data.local.entity.BillEntity,
    val suggestion: com.jiligulu.app.core.ai.AiBillDraft,
    val targetCategoryId: Long? = null
)

data class CategoryReclassificationResult(val moved: Int, val skipped: Int)
