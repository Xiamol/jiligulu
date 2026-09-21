package com.jiligulu.app.domain.category

/** Display-only aliases preserve existing category IDs, keywords and historical data. */
object CategoryLabels {
    fun displayName(raw: String): String = when (raw.lowercase()) {
        "eating" -> "吃饭"
        "drinking" -> "饮品"
        else -> raw.ifBlank { "未分类" }
    }
}
