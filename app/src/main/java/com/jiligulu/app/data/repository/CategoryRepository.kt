package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.CategoryDao
import com.jiligulu.app.data.local.AppDatabase
import androidx.room.withTransaction
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.category.CategorySuggestions
import com.jiligulu.app.domain.color.GoldenAnglePalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex

class CategoryRepository(private val categoryDao: CategoryDao, private val database: AppDatabase? = null) {
    private val creationLock = Mutex()

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll().map { list ->
        list.sortedBy { if (it.deletable) 0 else 1 }
    }

    suspend fun getAll(): List<CategoryEntity> = categories.first()

    /**
     * 新建分类（AI 自动建类 / 用户手动建类共用）。
     * 颜色：黄金角按当前数量取序，色值固化入库（PRD §5.4）。
     * 重名直接返回已有 id，防 AI 重复建类。
     */
    suspend fun createCategory(
        name: String,
        iconType: IconType = IconType.EMOJI,
        iconValue: String = "",
        iconSvg: String = "",
        keywords: String = "",
        createdBy: CreatedBy = CreatedBy.AI
    ): Long {
        suspend fun createChecked(): Long {
            val checkedName = requireNotNull(CategorySuggestions.name(name)) { "Invalid category name" }
            CategorySuggestions.existing(checkedName, categoryDao.findAllOnce())?.let { return it.id }
            val index = categoryDao.count()
            val checkedIcon = CategorySuggestions.icon(checkedName, iconValue)
            return categoryDao.insert(
                CategoryEntity(
                    name = checkedName,
                    iconType = if (iconType == IconType.EMOJI && checkedIcon.startsWith("builtin_")) IconType.BUILTIN else iconType,
                    iconValue = checkedIcon,
                    iconSvg = iconSvg,
                    colorHue = GoldenAnglePalette.hueFor(index),
                    colorIndex = index,
                    createdBy = createdBy,
                    keywords = CategorySuggestions.keywords(checkedName, keywords)
                )
            )
        }
        // Room's nested transaction context is safe when an AI command already owns the transaction.
        return database?.withTransaction { createChecked() } ?: creationLock.withLock { createChecked() }
    }

    /** 本地关键词 fallback 建议（记账页输入细则时实时提示） */
    fun suggest(text: String, categories: List<CategoryEntity>): CategoryEntity? =
        CategoryEngine.suggest(text, categories)
}
