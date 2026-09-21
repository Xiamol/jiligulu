package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.CategoryDao
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.color.GoldenAnglePalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class CategoryRepository(private val categoryDao: CategoryDao) {

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun getAll(): List<CategoryEntity> = categoryDao.observeAll().first()

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
        categoryDao.findByName(name.trim())?.let { return it.id }
        val index = categoryDao.count()
        return categoryDao.insert(
            CategoryEntity(
                name = name.trim(),
                iconType = iconType,
                iconValue = iconValue,
                iconSvg = iconSvg,
                colorHue = GoldenAnglePalette.hueFor(index),
                colorIndex = index,
                createdBy = createdBy,
                keywords = keywords
            )
        )
    }

    /** 本地关键词 fallback 建议（记账页输入细则时实时提示） */
    fun suggest(text: String, categories: List<CategoryEntity>): CategoryEntity? =
        CategoryEngine.suggest(text, categories)
}
