package com.jiligulu.app.ui.stats.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
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

/** Fixed value axis and scrollable 44dp day slots. Selection uses outline, label and color. */
@Composable
fun CashFlowBarChart(bars: List<DayBar>, selectedDayMillis: Long?, onSelectDay: (Long) -> Unit,
    color: Color, trackColor: Color, modifier: Modifier = Modifier) {
    val maxYuan = (bars.maxOfOrNull { it.amountFen } ?: 0L) / 100.0
    val axis = cashFlowAxis(maxYuan)
    val top = axis.top
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(selectedDayMillis, bars.size) {
        val index = bars.indexOfFirst { it.dayStartMillis == selectedDayMillis }
        if (index >= 0) scroll.animateScrollTo(with(density) { ((index - 2).coerceAtLeast(0) * 44).dp.roundToPx() })
    }
    fun tick(value: Double): String = java.math.BigDecimal.valueOf(value).setScale(if (top < 1) 3 else if (top < 100) 2 else 0, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    Column(modifier) {
        val selected = bars.find { it.dayStartMillis == selectedDayMillis }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f)) {
                Text(selected?.let { "♡ ${it.day}日 · ¥${Formatters.fenToYuanText(it.amountFen)}" } ?: "♡ 轻点一天",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            Text("金额 / 元", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(42.dp).height(154.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in axis.intervals downTo 0) Text((if (i == axis.intervals) "¥" else "") + tick(axis.step * i), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(154.dp)) {
                    repeat(axis.intervals + 1) { index ->
                        val y = size.height * index / axis.intervals
                        drawLine(trackColor.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                    }
                }
                Row(Modifier.horizontalScroll(scroll)) {
                    bars.forEach { bar ->
                        val selectedBar = bar.dayStartMillis == selectedDayMillis
                        Column(Modifier.width(44.dp).clickable { onSelectDay(bar.dayStartMillis) }
                            .semantics { contentDescription = "${bar.day}日，${Formatters.fenToYuanText(bar.amountFen)}元${if (selectedBar) "，已选中" else ""}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(154.dp).width(38.dp).background(if (selectedBar) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(22.dp)), contentAlignment = Alignment.BottomCenter) {
                                if (bar.amountFen > 0) {
                                    val ink = if (selectedBar) MaterialTheme.colorScheme.primary else color
                                    Box(Modifier.width(26.dp).height((bar.amountFen / 100.0 / top * 154).toFloat().coerceAtLeast(4f).dp)
                                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
                                        .background(Brush.verticalGradient(listOf(ink.copy(alpha = if (selectedBar) .72f else .42f), ink)))) {
                                        if (selectedBar) Box(Modifier.padding(top = 7.dp).width(10.dp).height(3.dp).align(Alignment.TopCenter)
                                            .background(Color.White.copy(alpha = .7f), RoundedCornerShape(50)))
                                    }
                                } else Box(Modifier.padding(bottom = 2.dp).size(5.dp).background(if (selectedBar) MaterialTheme.colorScheme.primary else trackColor, RoundedCornerShape(50)))
                            }
                            Surface(Modifier.padding(top = 7.dp), shape = RoundedCornerShape(50),
                                color = if (selectedBar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                Text(if (bar.isToday) "今天" else "${bar.day}日", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
