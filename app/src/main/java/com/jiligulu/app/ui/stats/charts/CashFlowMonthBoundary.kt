package com.jiligulu.app.ui.stats.charts

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import com.jiligulu.app.ui.components.EdgeSpringMotion
import java.time.YearMonth
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Only the next distinct, same-direction gesture within 1.5s can pass the touched month edge. */
internal class CashFlowMonthBoundary {
    var resetRevision by mutableIntStateOf(0)
        private set
    private var armedDirection = 0
    private var until = 0L
    fun begin(now: Long): Int {
        val allowed = if (now <= until) armedDirection else 0
        discard()
        return allowed
    }
    fun finish(blocked: Int, granted: Boolean, now: Long) {
        discard()
        if (!granted && blocked != 0) { armedDirection = blocked; until = now + 1_500 }
    }
    private fun discard() { armedDirection = 0; until = 0 }
    fun clear() { discard(); resetRevision++ }
}

private class CashFlowBoundaryGesture {
    var active = false
    var allowed = 0
    var primary = 0
    var blocked = 0
    var crossed = false
    var flingHitEdge = false
    var month: YearMonth = YearMonth.of(2026, 1)
}

/** Native scrolling continues inside the month. Only the excess at a protected edge springs. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.cashFlowMonthBoundary(viewport: CashFlowViewport, compressed: Boolean, days: Int,
    onCompactCross: ((YearMonth, Int) -> Unit)? = null): Modifier = composed {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val motion = remember(density) { EdgeSpringMotion(32f * density) }
    val gesture = remember(viewport, compressed, days) { CashFlowBoundaryGesture() }
    val cross by rememberUpdatedState(onCompactCross)
    val resetRevision = viewport.monthBoundary.resetRevision
    LaunchedEffect(resetRevision) { gesture.active = false; motion.reset() }
    fun currentMonth(): YearMonth {
        if (compressed) return cashFlowIndexMonth(viewport.months.currentPage)
        val layout = viewport.days.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }?.index
            ?: (viewport.days.firstVisibleItemIndex + days - 1).coerceAtMost(cashFlowDayCount - 1)
        return YearMonth.from(cashFlowIndexDate(last))
    }
    fun slotDistance(from: Int, to: Int, width: Int): Float = if (to >= from)
        (from until to).sumOf { cashFlowDaySlotWidthPx(it, width, days) }.toFloat()
    else -(to until from).sumOf { cashFlowDaySlotWidthPx(it, width, days) }.toFloat()
    fun consume(delta: Float, source: NestedScrollSource): Offset {
        if (!delta.isFinite() || delta == 0f) return Offset.Zero
        // Navigation stops the old scroll, but its pointer remains down. Keep consuming that
        // gesture through the remeasure and release so it cannot shift the new month's window.
        if (gesture.crossed) return Offset(delta, 0f)
        if (!gesture.active || !viewport.ready || viewport.navigating) return Offset.Zero
        val direction = if (delta > 0f) 1 else -1
        if (source == NestedScrollSource.UserInput && motion.touching && gesture.primary == 0) gesture.primary = direction
        val grant = gesture.allowed.takeIf { it == gesture.primary } ?: 0
        val firstMonth = YearMonth.of(1, 1)
        val lastMonth = YearMonth.of(9999, 12)
        if (grant != 0 && motion.touching) motion.clear()
        val lowerMonth = (if (compressed && grant == 1) gesture.month.minusMonths(1) else gesture.month).coerceAtLeast(firstMonth)
        val upperMonth = (if (compressed && grant == -1) gesture.month.plusMonths(1) else gesture.month).coerceAtMost(lastMonth)
        val toLower: Float
        val toUpper: Float
        if (compressed) {
            val width = viewport.months.layoutInfo.pageSize.toFloat()
            toLower = (cashFlowMonthIndex(lowerMonth) - viewport.months.currentPage - viewport.months.currentPageOffsetFraction) * width
            toUpper = (cashFlowMonthIndex(upperMonth) - viewport.months.currentPage - viewport.months.currentPageOffsetFraction) * width
        } else {
            val layout = viewport.days.layoutInfo
            val width = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1)
            val lower = cashFlowDayIndex(lowerMonth.atDay(1))
            val upper = (cashFlowDayIndex(upperMonth.atEndOfMonth()) - days + 1).coerceIn(0, cashFlowDayCount - days)
            toLower = slotDistance(viewport.days.firstVisibleItemIndex, lower, width) - viewport.days.firstVisibleItemScrollOffset
            toUpper = slotDistance(viewport.days.firstVisibleItemIndex, upper, width) - viewport.days.firstVisibleItemScrollOffset
        }
        val native = if (delta > 0f) delta.coerceAtMost((-toLower).coerceAtLeast(0f))
            else delta.coerceAtLeast(-toUpper.coerceAtLeast(0f))
        val excess = delta - native
        if (!compressed && abs(excess) > .01f && grant == direction && motion.touching && !gesture.crossed) {
            val calendarDirection = -direction
            val targetMonth = gesture.month.plusMonths(calendarDirection.toLong()).coerceIn(firstMonth, lastMonth)
            if (targetMonth != gesture.month) {
                gesture.crossed = true
                gesture.active = false
                val first = if (calendarDirection > 0) targetMonth.atDay(1) else targetMonth.atEndOfMonth().minusDays(days - 1L)
                motion.clear()
                viewport.prepareCompactMonth(first)
                val handler = cross
                if (handler != null) handler(targetMonth, calendarDirection)
                else scope.launch { viewport.navigate(CashFlowChartAnchor(first, targetMonth, dayCount = days), false) }
                return Offset(delta, 0f)
            }
        }
        if (abs(excess) > .01f && source == NestedScrollSource.UserInput && motion.touching) {
            gesture.blocked = direction
            motion.pull(excess)
        } else if (abs(excess) > .01f && !motion.touching && !gesture.flingHitEdge) {
            gesture.flingHitEdge = true
            gesture.blocked = direction
            viewport.monthBoundary.finish(direction, grant != 0, SystemClock.uptimeMillis())
            motion.beginTouch(); motion.pull(excess); motion.release(scope)
        }
        return Offset(excess, 0f)
    }
    val connection = remember(viewport, compressed, days, motion, gesture) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = consume(available.x, source)
        override suspend fun onPreFling(available: Velocity) =
            if (gesture.crossed || gesture.active && gesture.blocked != 0) Velocity(available.x, 0f) else Velocity.Zero
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!motion.touching) gesture.active = false
            return Velocity.Zero
        }
    } }
    DisposableEffect(viewport, compressed, days, motion) {
        onDispose { gesture.active = false; viewport.clearMonthBoundary(); motion.reset() }
    }
    this.pointerInput(viewport, compressed, days, motion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.beginTouch()
            motion.clear()
            gesture.active = true
            gesture.month = currentMonth()
            gesture.allowed = viewport.monthBoundary.begin(SystemClock.uptimeMillis())
            gesture.primary = 0
            gesture.blocked = 0
            gesture.crossed = false
            gesture.flingHitEdge = false
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) break
                    if (event.changes.none { it.pressed }) { completed = true; break }
                }
            } finally {
                val granted = gesture.allowed != 0 && gesture.allowed == gesture.primary
                viewport.monthBoundary.finish(if (completed) gesture.blocked else 0, granted, SystemClock.uptimeMillis())
                if (!completed) { gesture.allowed = 0; gesture.active = false }
                motion.release(scope)
            }
        }
    }.nestedScroll(connection).graphicsLayer { translationX = motion.offset }
}
