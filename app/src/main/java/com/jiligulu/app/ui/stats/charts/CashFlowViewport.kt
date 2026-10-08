package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.YearMonth

data class CashFlowChartAnchor(val firstDay: LocalDate, val month: YearMonth,
    val revision: Long = 0, val monthRevision: Long = revision, val followsToday: Boolean = false, val dayCount: Int = 10)

private val firstDay = LocalDate.of(1, 1, 1).toEpochDay()
private val lastDay = LocalDate.of(9999, 12, 31).toEpochDay()
internal val cashFlowDayCount = (lastDay - firstDay + 1).toInt()
internal const val cashFlowMonthCount = 9999 * 12
internal fun cashFlowDayIndex(date: LocalDate) = (date.toEpochDay() - firstDay).coerceIn(0, cashFlowDayCount - 1L).toInt()
internal fun cashFlowIndexDate(index: Int): LocalDate = LocalDate.ofEpochDay(firstDay + index.coerceIn(0, cashFlowDayCount - 1))
internal fun cashFlowMonthIndex(month: YearMonth) = ((month.year - 1) * 12 + month.monthValue - 1).coerceIn(0, cashFlowMonthCount - 1)
internal fun cashFlowIndexMonth(index: Int): YearMonth = index.coerceIn(0, cashFlowMonthCount - 1).let { YearMonth.of(it / 12 + 1, it % 12 + 1) }

/** Every ten consecutive slots fill the pixel viewport exactly, including fractional densities. */
internal fun cashFlowDaySlotWidthPx(index: Int, plotWidthPx: Int, days: Int = 10): Int {
    val slot = Math.floorMod(index, days)
    return cashFlowDateSlotWidthPx(slot, days, plotWidthPx)
}
internal fun cashFlowDateSlotWidthPx(index: Int, count: Int, plotWidthPx: Int): Int =
    ((index + 1) * plotWidthPx / count - index * plotWidthPx / count).coerceAtLeast(1)

/** Scroll identity is independent of incoming data and of taps on a day. */
@OptIn(ExperimentalFoundationApi::class)
class CashFlowViewport(val days: LazyListState, val months: PagerState) {
    internal val monthBoundary = CashFlowMonthBoundary()
    internal fun clearMonthBoundary() = monthBoundary.clear()
    private var navigationTicket = 0L
    internal var appliedDayRevision = Long.MIN_VALUE
    internal var appliedMonthRevision = Long.MIN_VALUE
    internal var navigating by mutableStateOf(false)
        private set
    internal var ready by mutableStateOf(false)
        private set

    internal suspend fun navigate(anchor: CashFlowChartAnchor, compressedMonth: Boolean, force: Boolean = true) {
        clearMonthBoundary()
        val ticket = ++navigationTicket
        var completed = false
        navigating = true
        ready = false
        try {
            if (compressedMonth) {
                val target = cashFlowMonthIndex(anchor.month)
                if (force || target != months.currentPage) months.scrollToPage(target)
            } else {
                val target = cashFlowDayIndex(anchor.firstDay)
                if (force || target != days.firstVisibleItemIndex) days.scrollToItem(target)
            }
            completed = true
        } finally {
            if (ticket == navigationTicket) {
                if (completed) {
                    if (compressedMonth) appliedMonthRevision = anchor.monthRevision
                    else appliedDayRevision = anchor.revision
                }
                navigating = false
                ready = true
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCashFlowViewport(anchor: CashFlowChartAnchor): CashFlowViewport {
    val days = rememberLazyListState(initialFirstVisibleItemIndex = cashFlowDayIndex(anchor.firstDay))
    val months = rememberPagerState(initialPage = cashFlowMonthIndex(anchor.month)) { cashFlowMonthCount }
    return remember(days, months) { CashFlowViewport(days, months) }
}
