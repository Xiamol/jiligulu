package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.util.Formatters
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

/** All visible dates share the available width. Only the selected month bar displays its amount. */
@Composable
fun CashFlowBarChart(bars: List<DayBar>, selectedDayMillis: Long?, onSelectDay: (Long) -> Unit,
    color: Color, trackColor: Color, modifier: Modifier = Modifier, onVisibleRange: (Long, Long) -> Unit = { _, _ -> },
    compressedMonth: Boolean = false, onShiftWindow: (Int) -> Unit = {}) {
    val maxYuan = (bars.maxOfOrNull { it.amountFen } ?: 0L) / 100.0
    val axis = cashFlowAxis(maxYuan)
    val top = axis.top
    val monthTicks = remember(bars.firstOrNull()?.dayStartMillis, bars.lastOrNull()?.day, selectedDayMillis) {
        cashFlowMonthDateTicks(bars.lastOrNull()?.day ?: 0,
            bars.firstOrNull { it.dayStartMillis == selectedDayMillis }?.day)
    }
    val currentShift by rememberUpdatedState(onShiftWindow)
    LaunchedEffect(bars.firstOrNull()?.dayStartMillis, bars.lastOrNull()?.dayStartMillis) {
        if (bars.isNotEmpty()) onVisibleRange(bars.first().dayStartMillis, bars.last().dayStartMillis)
    }
    fun tick(value: Double): String = java.math.BigDecimal.valueOf(value).setScale(if (top < 1) 3 else if (top < 100) 2 else 0, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    Column(modifier.pointerInput(compressedMonth) {
        var drag = 0f
        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
            onDragEnd = {
                if (drag > 32.dp.toPx()) currentShift(-1)
                else if (drag < -32.dp.toPx()) currentShift(1)
                drag = 0f
            }, onHorizontalDrag = { change, delta -> drag += delta; change.consume() })
    }) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(34.dp).padding(top = 20.dp).height(154.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in axis.intervals downTo 0) Text((if (i == axis.intervals) "¥" else "") + tick(axis.step * i), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val slot = maxWidth / bars.size.coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().padding(top = 20.dp).height(154.dp)) {
                    repeat(axis.intervals + 1) { index ->
                        val y = size.height * index / axis.intervals
                        drawLine(trackColor.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    bars.forEach { bar ->
                        val selectedBar = bar.dayStartMillis == selectedDayMillis
                        val showDate = !compressedMonth || bar.day in monthTicks
                        Column(Modifier.width(slot).clickable { onSelectDay(bar.dayStartMillis) }
                            .semantics { contentDescription = "${bar.day}日，${Formatters.fenToYuanText(bar.amountFen)}元${if (selectedBar) "，已选中" else ""}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(174.dp).width((slot - 1.dp).coerceAtLeast(1.dp)).background(if (selectedBar) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(22.dp)), contentAlignment = Alignment.BottomCenter) {
                                if (bar.amountFen > 0) {
                                    val ink = color
                                    Box(Modifier.width((slot * if (compressedMonth) .65f else .6f).coerceAtMost(22.dp)).height((bar.amountFen / 100.0 / top * 154).toFloat().coerceAtLeast(4f).dp)
                                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
                                        .background(Brush.verticalGradient(listOf(ink.copy(alpha = if (selectedBar) .72f else .42f), ink)))) {
                                        if (selectedBar && !compressedMonth) Box(Modifier.padding(top = 7.dp).width(7.dp).height(3.dp).align(Alignment.TopCenter)
                                            .background(Color.White.copy(alpha = .7f), RoundedCornerShape(50)))
                                    }
                                    if (!compressedMonth) Text(compactCashFlowAmount(bar.amountFen),
                                        Modifier.align(Alignment.BottomCenter).offset(y = -((bar.amountFen / 100.0 / top * 154).toFloat().coerceAtLeast(4f) + 3).dp),
                                        fontSize = 9.sp, maxLines = 1,
                                        color = if (selectedBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                } else Box(Modifier.padding(bottom = 2.dp).size(5.dp).background(if (selectedBar) MaterialTheme.colorScheme.primary else trackColor, RoundedCornerShape(50)))
                            }
                            Surface(Modifier.padding(top = 4.dp).height(24.dp), shape = RoundedCornerShape(50),
                                color = if (selectedBar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                Text(if (!showDate) "" else if (!compressedMonth && bar.isToday) "今天" else "${bar.day}",
                                    Modifier.requiredWidth(if (compressedMonth) 24.dp else slot).padding(vertical = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = if (compressedMonth) 9.sp else 10.sp,
                                    color = if (selectedBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (compressedMonth) bars.indexOfFirst { it.dayStartMillis == selectedDayMillis }.takeIf { it >= 0 }?.let { index ->
                    val selected = bars[index]
                    val labelWidth = 92.dp.coerceAtMost(maxWidth)
                    Text("${selected.day}日 · ¥${Formatters.fenToYuanText(selected.amountFen)}",
                        Modifier.width(labelWidth).offset(x = (slot * (index + .5f) - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f), RoundedCornerShape(8.dp)).padding(vertical = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                }
            }
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
