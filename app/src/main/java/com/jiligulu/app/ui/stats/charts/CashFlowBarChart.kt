package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.jiligulu.app.core.util.Formatters
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.*

data class DayBar(val day: Int, val dayStartMillis: Long, val amountFen: Long, val isToday: Boolean)

internal data class CashFlowAxis(val step: Double, val intervals: Int) { val top get() = step * intervals }
internal fun cashFlowAxis(maxYuan: Double): CashFlowAxis {
    if (!maxYuan.isFinite() || maxYuan <= 0) return CashFlowAxis(.25, 4)
    val power = floor(log10(maxYuan)).toInt()
    val candidates = (-2..1).flatMap { offset ->
        listOf(1.0, 1.1, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 5.5, 6.0, 7.5, 8.0, 10.0).flatMap { factor ->
            (3..6).map { count -> CashFlowAxis(factor * 10.0.pow(power + offset), count) }
        }
    }.filter { maxYuan / it.top in .80.. .95 }
    return candidates.minByOrNull { abs(maxYuan / it.top - .9) + abs(it.intervals - 4) * .01 }
        ?: CashFlowAxis(10.0.pow(power), ceil(maxYuan / 10.0.pow(power)).toInt() + 1)
}
internal fun cashFlowAxisTop(maxYuan: Double): Double = cashFlowAxis(maxYuan).top

/** A selected date always stays visible; neighboring regular ticks leave room for its label. */
internal fun cashFlowMonthDateTicks(daysInMonth: Int, selectedDay: Int?): Set<Int> {
    if (daysInMonth < 1) return emptySet()
    val selected = selectedDay?.takeIf { it in 1..daysInMonth }
    val regular = buildSet {
        add(1)
        for (day in 5..daysInMonth step 5) if (daysInMonth - day >= 2) add(day)
        add(daysInMonth)
    }
    return regular.filterTo(linkedSetOf()) { day -> selected == null || day == selected || abs(day - selected) > 1 }
        .apply { if (selected != null) add(selected) }
}

/** Position a full-width label inside the actual pixel viewport, including rounded density boundaries. */
internal fun cashFlowDateLabelLeftPx(index: Int, count: Int, plotWidthPx: Int, labelWidthPx: Int): Int {
    if (count <= 0 || plotWidthPx <= 0) return 0
    val width = labelWidthPx.coerceIn(0, plotWidthPx)
    val center = plotWidthPx.toDouble() * (index.coerceIn(0, count - 1) + .5) / count
    return (center - width / 2.0).roundToInt().coerceIn(0, plotWidthPx - width)
}

/** Native date scrolling: only data queries move their small cache, never the scroll identity. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CashFlowBarChart(bars: List<DayBar>, selectedDayMillis: Long?, onSelectDay: (Long) -> Unit,
    color: Color, trackColor: Color, modifier: Modifier = Modifier, onVisibleRange: (Long, Long) -> Unit = { _, _ -> },
    compressedMonth: Boolean = false, onShiftWindow: (Int) -> Unit = {},
    anchor: CashFlowChartAnchor? = null, followToday: Boolean = false, todayMillis: Long? = null,
    onCompactViewport: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    onMonthViewport: (Long, Boolean) -> Unit = { _, _ -> }) {
    val zone = remember { ZoneId.systemDefault() }
    fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun millis(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val today = todayMillis?.let(::date) ?: LocalDate.now(zone)
    val autoFollow = anchor?.followsToday ?: followToday
    val requested = if (autoFollow) CashFlowChartAnchor(today.minusDays(9), YearMonth.from(today),
        anchor?.revision ?: 0, anchor?.monthRevision ?: 0, followsToday = true)
    else anchor ?: CashFlowChartAnchor(bars.firstOrNull()?.dayStartMillis?.let(::date)
        ?: selectedDayMillis?.let(::date) ?: today, YearMonth.from(selectedDayMillis?.let(::date) ?: today))
    val viewport = rememberCashFlowViewport(requested)
    val visibleCallback by rememberUpdatedState(onVisibleRange)
    val compactCallback by rememberUpdatedState(onCompactViewport)
    val monthCallback by rememberUpdatedState(onMonthViewport)
    val byDate = remember(bars) { bars.associateBy { date(it.dayStartMillis).toEpochDay() } }
    var appliedDayRevision by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var appliedMonthRevision by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(if (compressedMonth) requested.monthRevision else requested.revision, compressedMonth) {
        val revision = if (compressedMonth) requested.monthRevision else requested.revision
        val previous = if (compressedMonth) appliedMonthRevision else appliedDayRevision
        viewport.navigate(requested, compressedMonth, force = previous != revision)
        if (compressedMonth) appliedMonthRevision = revision else appliedDayRevision = revision
    }
    // A midnight wake follows today only before the user has chosen or scrolled dates.
    LaunchedEffect(today) {
        if (autoFollow) viewport.navigate(CashFlowChartAnchor(today.minusDays(9), YearMonth.from(today)), compressedMonth)
    }
    LaunchedEffect(viewport, compressedMonth) {
        if (compressedMonth) snapshotFlow {
            if (!viewport.ready || viewport.navigating) null
            else viewport.months.currentPage to viewport.months.isScrollInProgress
        }.distinctUntilChanged().collect { report ->
            report?.let { (page, moving) ->
                val month = cashFlowIndexMonth(page)
                visibleCallback(millis(month.atDay(1)), millis(month.atEndOfMonth()))
                monthCallback(millis(month.atDay(1)), moving)
            }
        } else snapshotFlow {
            val layout = viewport.days.layoutInfo
            val visible = layout.visibleItemsInfo.filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
            if (!viewport.ready || viewport.navigating || visible.isEmpty()) null
            else Triple(visible.first().index, visible.last().index, viewport.days.isScrollInProgress)
        }.distinctUntilChanged().collect { report ->
            report?.let { (first, last, moving) ->
                val start = millis(cashFlowIndexDate(first)); val end = millis(cashFlowIndexDate(last))
                visibleCallback(start, end)
                compactCallback(start, end, moving)
            }
        }
    }
    val visibleDays by remember(viewport) { derivedStateOf {
        val layout = viewport.days.layoutInfo
        val visible = layout.visibleItemsInfo.filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
        (visible.firstOrNull()?.index ?: cashFlowDayIndex(requested.firstDay)) to
            (visible.lastOrNull()?.index ?: (cashFlowDayIndex(requested.firstDay) + 9).coerceAtMost(cashFlowDayCount - 1))
    } }
    val currentMonth = cashFlowIndexMonth(viewport.months.currentPage)
    val moving = if (compressedMonth) viewport.months.isScrollInProgress else viewport.days.isScrollInProgress
    val maxYuan = remember(byDate, visibleDays, currentMonth, compressedMonth) {
        byDate.asSequence().filter { (epoch, _) ->
            if (compressedMonth) YearMonth.from(LocalDate.ofEpochDay(epoch)) == currentMonth
            else epoch in cashFlowIndexDate(visibleDays.first).toEpochDay()..cashFlowIndexDate(visibleDays.second).toEpochDay()
        }.maxOfOrNull { it.value.amountFen }?.div(100.0) ?: 0.0
    }
    var fittedAxis by remember { mutableStateOf(cashFlowAxis(maxYuan)) }
    val axisTop = remember { Animatable(fittedAxis.top.toFloat()) }
    LaunchedEffect(maxYuan, moving) {
        if (moving) axisTop.stop()
        else {
            fittedAxis = cashFlowAxis(maxYuan)
            axisTop.animateTo(fittedAxis.top.toFloat(), tween(180))
        }
    }
    val top = axisTop.value
    fun tick(value: Double): String = java.math.BigDecimal.valueOf(value)
        .setScale(if (top < 1) 3 else if (top < 100) 2 else 0, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    Row(modifier) {
        Column(Modifier.width(34.dp).padding(top = 20.dp).height(154.dp).testTag("cash-flow-y-axis"), verticalArrangement = Arrangement.SpaceBetween) {
            for (i in fittedAxis.intervals downTo 0) Text((if (i == fittedAxis.intervals) "¥" else "") + tick(top.toDouble() * i / fittedAxis.intervals),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.weight(1f).height(202.dp).clipToBounds()) {
            val density = LocalDensity.current
            val plotWidth = maxWidth
            val plotPixelWidth = constraints.maxWidth
            Canvas(Modifier.fillMaxWidth().padding(top = 20.dp).height(154.dp)) {
                repeat(fittedAxis.intervals + 1) { index ->
                    val y = size.height * index / fittedAxis.intervals
                    drawLine(trackColor.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                }
            }
            if (!compressedMonth) LazyRow(state = viewport.days, modifier = Modifier.fillMaxSize().testTag("cash-flow-day-strip")) {
                items(count = cashFlowDayCount, key = { cashFlowIndexDate(it).toEpochDay() }) { index ->
                    val day = cashFlowIndexDate(index)
                    val width = with(density) { cashFlowDaySlotWidthPx(index, plotPixelWidth).toDp() }
                    CashFlowDayCell(day, byDate[day.toEpochDay()], selectedDayMillis, today, top, color, trackColor,
                        width, false, onSelectDay)
                }
            } else HorizontalPager(state = viewport.months, modifier = Modifier.fillMaxSize().testTag("cash-flow-month-pager"), beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(viewport.months, pagerSnapDistance = PagerSnapDistance.atMost(1)),
                key = { it }) { page ->
                val month = cashFlowIndexMonth(page)
                val monthDates = remember(month) { (1..month.lengthOfMonth()).map(month::atDay) }
                val selectedDate = selectedDayMillis?.let(::date)?.takeIf { YearMonth.from(it) == month }
                val ticks = remember(month, selectedDate) { cashFlowMonthDateTicks(month.lengthOfMonth(), selectedDate?.dayOfMonth) }
                val slot = plotWidth / month.lengthOfMonth()
                Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth()) {
                        monthDates.forEachIndexed { index, day ->
                            val width = with(density) { cashFlowDateSlotWidthPx(index, monthDates.size, plotPixelWidth).toDp() }
                            CashFlowDayCell(day, byDate[day.toEpochDay()], selectedDayMillis,
                                today, top, color, trackColor, width, true, onSelectDay)
                        }
                    }
                    val labelWidth = 24.dp.coerceAtMost(plotWidth)
                    val labelPixels = with(density) { labelWidth.roundToPx() }
                    Box(Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp)) {
                        monthDates.forEachIndexed { index, day ->
                            if (day.dayOfMonth in ticks) Surface(
                                Modifier.offset { IntOffset(cashFlowDateLabelLeftPx(index, monthDates.size, plotPixelWidth, labelPixels), 0) }
                                    .width(labelWidth).height(24.dp).clickable { onSelectDay(millis(day)) },
                                shape = RoundedCornerShape(50), color = if (day == selectedDate) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                Text("${day.dayOfMonth}", Modifier.padding(vertical = 4.dp), maxLines = 1, fontSize = 9.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = if (day == selectedDate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                selectedDate?.let { day ->
                    val bar = byDate[day.toEpochDay()]
                    val labelWidth = 92.dp.coerceAtMost(plotWidth)
                    Text("${day.dayOfMonth}日 · ${bar?.let { "¥${Formatters.fenToYuanText(it.amountFen)}" } ?: "加载中"}",
                        Modifier.width(labelWidth).offset(x = (slot * (day.dayOfMonth - .5f) - labelWidth / 2).coerceIn(0.dp, plotWidth - labelWidth))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f), RoundedCornerShape(8.dp)).padding(vertical = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                }
                }
            }
        }
    }
}

@Composable
private fun CashFlowDayCell(day: LocalDate, bar: DayBar?, selectedDayMillis: Long?, today: LocalDate, top: Float,
    color: Color, trackColor: Color, slot: Dp, compressed: Boolean, onSelectDay: (Long) -> Unit) {
    val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val selected = start == selectedDayMillis
    val height = ((bar?.amountFen ?: 0) / 100.0 / top.coerceAtLeast(.01f) * 154).toFloat().coerceIn(4f, 154f).dp
    Column(Modifier.width(slot).clickable { onSelectDay(start) }.semantics {
        contentDescription = "${day.monthValue}月${day.dayOfMonth}日，" + (bar?.let { "${Formatters.fenToYuanText(it.amountFen)}元" } ?: "加载中") + if (selected) "，已选中" else ""
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(174.dp).width((slot - 1.dp).coerceAtLeast(1.dp)).background(
            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f) else Color.Transparent,
            RoundedCornerShape(22.dp)), contentAlignment = Alignment.BottomCenter) {
            if (bar == null) Box(Modifier.width((slot * .55f).coerceAtMost(18.dp)).height(18.dp)
                .background(trackColor.copy(alpha = .35f), RoundedCornerShape(5.dp)))
            else if (bar.amountFen > 0) {
                Box(Modifier.width((slot * if (compressed) .65f else .6f).coerceAtMost(22.dp)).height(height)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
                    .background(Brush.verticalGradient(listOf(color.copy(alpha = if (selected) .72f else .42f), color)))) {
                    if (selected && !compressed) Box(Modifier.padding(top = 7.dp).width(7.dp).height(3.dp).align(Alignment.TopCenter)
                        .background(Color.White.copy(alpha = .7f), RoundedCornerShape(50)))
                }
                if (!compressed) Text(compactCashFlowAmount(bar.amountFen), Modifier.align(Alignment.BottomCenter).offset(y = -(height + 3.dp)),
                    fontSize = 9.sp, maxLines = 1, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            } else Box(Modifier.padding(bottom = 2.dp).size(5.dp).background(if (selected) MaterialTheme.colorScheme.primary else trackColor, RoundedCornerShape(50)))
        }
        if (!compressed) Surface(Modifier.padding(top = 4.dp).height(24.dp), shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
            Text(if (day == today) "今天" else "${day.dayOfMonth}", Modifier.requiredWidth(slot).padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
/** Narrow slots retain readable amounts; tapping still shows exact cents in the daily distribution. */
internal fun compactCashFlowAmount(fen: Long): String = when {
    fen in 1L..99L -> Formatters.fenToYuanText(fen)
    fen >= 1_000_000L -> java.math.BigDecimal.valueOf(fen, 6).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "万"
    fen >= 100_000L -> java.math.BigDecimal.valueOf(fen, 5).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "k"
    else -> java.math.BigDecimal.valueOf(fen, 2).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
