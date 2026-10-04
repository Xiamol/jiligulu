package com.jiligulu.app.data.prefs

/** Stable stored IDs keep skin choices intact when titles or artwork change. */
enum class LittleWorldSkin(val id: String, val title: String) {
    MOONLIGHT("moonlight", "月光星瓶"),
    SCRAPBOOK("scrapbook", "手账拼贴"),
    STRAWBERRY("strawberry", "草莓奶油"),
    WOODLAND("woodland", "森林邮局");

    companion object {
        val DEFAULT = SCRAPBOOK
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
