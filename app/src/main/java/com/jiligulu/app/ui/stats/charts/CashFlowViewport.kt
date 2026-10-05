package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/** Both chart styles retain the same horizontal position when the user changes their view. */
class CashFlowViewport(val scroll: ScrollState) {
    private var anchoredDay: Long? = null
    private var anchoredMonth: Long? = null

    suspend fun anchorSelection(bars: List<DayBar>, selected: Long?, slotPx: Float) {
        if (bars.isEmpty() || slotPx <= 0f) return
        val month = bars.first().dayStartMillis
        if (selected == anchoredDay && month == anchoredMonth) return
        val index = bars.indexOfFirst { it.dayStartMillis == selected }
        anchoredDay = selected
        anchoredMonth = month
        if (index >= 0) scroll.animateScrollTo(((index - 2).coerceAtLeast(0) * slotPx).roundToInt())
    }
}

@Composable
fun rememberCashFlowViewport(): CashFlowViewport {
    val scroll = rememberScrollState()
    return remember(scroll) { CashFlowViewport(scroll) }
}
