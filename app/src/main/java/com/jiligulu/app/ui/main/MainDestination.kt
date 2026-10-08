package com.jiligulu.app.ui.main

/** Stable identities survive tab-order changes; numeric positions are only rendering coordinates. */
internal enum class MainDestination(val id: String, val label: String, val tag: String) {
    LEDGER("ledger", "账本", "main-tab-home"),
    STATISTICS("statistics", "统计", "main-tab-stats"),
    WORLD("world", "小窝", "main-tab-world");
    val index: Int get() = ordinal
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: LEDGER
        fun fromIndex(index: Int) = entries[index.coerceIn(0, entries.lastIndex)]
    }
}
