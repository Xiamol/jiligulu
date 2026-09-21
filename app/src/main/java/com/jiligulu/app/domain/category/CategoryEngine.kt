package com.jiligulu.app.domain.category

import com.jiligulu.app.data.local.entity.CategoryEntity

/**
 * 本地分类引擎 —— PRD §4「AI 分类 + 本地关键词规则 fallback」的本地部分。
 * 纯 Kotlin 逻辑，不碰 Android API，可直接单测。
 */
object CategoryEngine {

    /**
     * 用细则/备注文本匹配分类关键词，命中即返回该分类；都不命中返回 null。
     * 长关键词优先（"矿泉水"先于"水"），避免短词误伤。
     */
    fun suggest(text: String, categories: List<CategoryEntity>): CategoryEntity? {
        if (text.isBlank()) return null
        val input = text.trim().lowercase()
        var best: CategoryEntity? = null
        var bestLen = 0
        for (category in categories) {
            category.keywords.split(',', '，')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .forEach { kw ->
                    if (input.contains(kw) && kw.length > bestLen) {
                        best = category
                        bestLen = kw.length
                    }
                }
        }
        return best
    }
}
