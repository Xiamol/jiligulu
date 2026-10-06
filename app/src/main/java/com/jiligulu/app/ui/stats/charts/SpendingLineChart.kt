package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.jiligulu.app.core.util.Formatters
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

 data class SpendingLinePoint(val dayStartMillis: Long, val actualFen: Long?, val averageFen: Long?)

/** One whole month fits the plot. Actual totals stop at today; a point tap selects its nearest day. */
@Composable
fun SpendingLineChart(
    bars: List<DayBar>, points: List<SpendingLinePoint>, selectedDayMillis: Long?,
    actualVisible: Boolean, averageVisible: Boolean,
    actualColor: Color, averageColor: Color, onSelectDay: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val pointMap = remember(points) { points.associateBy { it.dayStartMillis } }
    val maximum = points.maxOfOrNull { point ->
        maxOf(if (actualVisible) point.actualFen ?: 0 else 0, if (averageVisible) point.averageFen ?: 0 else 0)
    } ?: 0L
    val axis = cashFlowAxis(maximum / 100.0)
    val top = axis.top
    val density = LocalDensity.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
    val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)
    val paper = MaterialTheme.colorScheme.surface
    val latestSelect by rememberUpdatedState(onSelectDay)
    val measurer = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.labelSmall
    fun tick(value: Double) = java.math.BigDecimal.valueOf(value).setScale(if (top < 1) 3 else if (top < 100) 2 else 0,
        java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    Row(modifier.testTag("spending-line-chart")) {
        Column(Modifier.width(34.dp).height(162.dp).padding(top = 34.dp), verticalArrangement = Arrangement.SpaceBetween) {
            for (i in axis.intervals downTo 0) Text((if (i == axis.intervals) "¥" else "") + tick(axis.step * i),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val labelWidth = 24.dp
            val inset = labelWidth / 2
            val plotWidth = (maxWidth - labelWidth).coerceAtLeast(1.dp)
            val insetPx = with(density) { inset.toPx() }
            val plotWidthPx = with(density) { plotWidth.toPx() }
            val lastIndex = bars.lastIndex.coerceAtLeast(1)
            fun x(index: Int): Float = insetPx + plotWidthPx * index / lastIndex
            Column {
                Canvas(Modifier.fillMaxWidth().height(174.dp).pointerInput(bars, plotWidthPx, insetPx) {
                    detectTapGestures { offset ->
                        val index = (((offset.x - insetPx) / plotWidthPx) * lastIndex).roundToInt()
                            .coerceIn(0, bars.lastIndex.coerceAtLeast(0))
                        bars.getOrNull(index)?.let { latestSelect(it.dayStartMillis) }
                    }
                }) {
                    val plotHeight = 128.dp.toPx()
                    val plotTop = 34.dp.toPx()
                    repeat(axis.intervals + 1) { index ->
                        val y = plotTop + plotHeight * index / axis.intervals
                        drawLine(gridColor, Offset(insetPx, y), Offset(size.width - insetPx, y), 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                    }
                    val selected = bars.indexOfFirst { it.dayStartMillis == selectedDayMillis }
                    if (selected >= 0) drawLine(selectionColor, Offset(x(selected), plotTop), Offset(x(selected), size.height), 7.dp.toPx())
                    fun y(fen: Long) = plotTop + plotHeight * (1 - (fen / 100.0 / top).coerceIn(0.0, 1.0)).toFloat()
                    fun line(color: Color, dashed: Boolean, value: (SpendingLinePoint) -> Long?) {
                        var prior: Offset? = null
                        val path = Path()
                        bars.forEachIndexed { index, bar ->
                            val amount = pointMap[bar.dayStartMillis]?.let(value)
                            if (amount == null) { prior = null; return@forEachIndexed }
                            val p = Offset(x(index), y(amount))
                            if (prior == null) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            prior = p
                        }
                        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
                        bars.forEachIndexed { index, bar ->
                            val amount = pointMap[bar.dayStartMillis]?.let(value) ?: return@forEachIndexed
                            // Keep the mean line clean; the final value is labelled at its endpoint.
                            if (dashed && index != selected) return@forEachIndexed
                            val p = Offset(x(index), y(amount))
                            val radius = if (index == selected) 4.5.dp.toPx() else 1.6.dp.toPx()
                            drawCircle(paper, radius, p)
                            drawCircle(color, radius, p, style = Stroke(1.2.dp.toPx()))
                        }
                    }
                    if (averageVisible) line(averageColor, true) { it.averageFen }
                    if (actualVisible) line(actualColor, false) { it.actualFen }
                    fun amountLabel(amount: Long, position: Offset, color: Color, below: Boolean) {
                        val layout = measurer.measure(Formatters.fenToYuanText(amount), amountStyle.copy(color = color))
                        val left = (position.x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                        val top = (position.y + if (below) 8.dp.toPx() else -layout.size.height - 9.dp.toPx())
                            .coerceIn(25.dp.toPx(), (size.height - layout.size.height).coerceAtLeast(25.dp.toPx()))
                        drawText(layout, topLeft = Offset(left, top))
                    }
                    if (actualVisible && selected >= 0) {
                        pointMap[bars[selected].dayStartMillis]?.actualFen?.let { amount ->
                            amountLabel(amount, Offset(x(selected), y(amount)), actualColor, below = false)
                        }
                    }
                    if (averageVisible) {
                        val last = bars.indexOfLast { pointMap[it.dayStartMillis]?.averageFen != null }
                        if (last >= 0) pointMap[bars[last].dayStartMillis]?.averageFen?.let { amount ->
                            val actual = pointMap[bars[last].dayStartMillis]?.actualFen
                            val nearSelected = last == selected && actualVisible && actual != null && kotlin.math.abs(y(actual) - y(amount)) < 24.dp.toPx()
                            amountLabel(amount, Offset(x(last), y(amount)), averageColor, below = nearSelected || y(amount) < plotTop + 22.dp.toPx())
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(28.dp)) {
                    bars.forEachIndexed { index, bar ->
                        if (bar.day !in listOf(1, 5, 10, 15, 20, 25) && index != bars.lastIndex) return@forEachIndexed
                        val selected = bar.dayStartMillis == selectedDayMillis
                        Text("${bar.day}", Modifier.offset(x = plotWidth * (index.toFloat() / lastIndex)).width(labelWidth)
                            .clickable { onSelectDay(bar.dayStartMillis) }.padding(top = 7.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
