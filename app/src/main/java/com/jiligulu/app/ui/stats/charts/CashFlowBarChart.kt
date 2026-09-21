package com.jiligulu.app.ui.stats.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/** 收支长河：一天一根柱 */
data class DayBar(
    val day: Int,               // 几号
    val dayStartMillis: Long,   // 当天 0 点
    val amountFen: Long,
    val isToday: Boolean
)

/**
 * 收支长河柱状图（PRD：只显示每日总收入/总支出，收支可切换）。
 *
 * 性能设计：
 * - 柱列表由 ViewModel 预计算（stateIn），UI 层不遍历账单；
 * - 最大值 remember(bars) 缓存；
 * - 高度动画单 Animatable，数据（bars）变化才重播；
 * - 点击某天 → onSelectDay，联动「今日瓜分」，同月换日不触发数据库重查。
 */
@Composable
fun CashFlowBarChart(
    bars: List<DayBar>,
    selectedDayMillis: Long?,
    onSelectDay: (Long) -> Unit,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val maxFen = remember(bars) {
        (bars.maxOfOrNull { it.amountFen } ?: 0L).coerceAtLeast(1L)
    }

    // 动画：只在「存活期间数据真的变了」才播；滚动滑回重组时直接满值静显（防滚动重绘抖动）
    val progress = remember { Animatable(1f) }
    var prevBars by remember { mutableStateOf(bars) }
    LaunchedEffect(bars) {
        if (bars !== prevBars) {
            prevBars = bars
            progress.snapTo(0f)
            progress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
    }

    Canvas(
        modifier.pointerInput(bars) {
            detectTapGestures { offset ->
                if (bars.isEmpty()) return@detectTapGestures
                val index = (offset.x / size.width * bars.size)
                    .toInt().coerceIn(0, bars.lastIndex)
                onSelectDay(bars[index].dayStartMillis)
            }
        }
    ) {
        val n = bars.size
        if (n == 0) return@Canvas
        val slotW = size.width / n
        val barW = slotW * 0.56f
        val corner = CornerRadius(barW / 2f, barW / 2f)
        val p = progress.value
        bars.forEachIndexed { i, bar ->
            val ratio = bar.amountFen.toFloat() / maxFen
            val h = (ratio * size.height * 0.92f * p)
                .coerceAtLeast(if (bar.amountFen > 0) barW * 0.5f else barW * 0.18f)
            val x = i * slotW + (slotW - barW) / 2f
            val barColor = when {
                bar.dayStartMillis == selectedDayMillis -> color
                bar.amountFen > 0 -> color.copy(alpha = 0.82f)
                else -> trackColor
            }
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
                cornerRadius = corner
            )
        }
    }
}
