package com.jiligulu.app.ui.persona

/** The companion's current line. Its lifetime is controlled by the ViewModel, not the UI. */
data class BubbleMessage(
    val id: Long,
    val text: String,
    val kind: Kind
) {
    enum class Kind { GREET, WATER, IDLE }
}
