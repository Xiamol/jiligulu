package com.jiligulu.app.ui.stats.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/** 环形图的一段（今日瓜分=分类；余粮环=已用/剩余） */
data class DonutSlice(
    val key: Any,
    val label: String,
    val valueFen: Long,
    val color: Color
)

private class ArcSpec(
    val key: Any,
    val color: Color,
    val startDeg: Float,      // 绘制起点（含间隙收缩）
    val sweepDeg: Float,      // 绘制跨度（含间隙收缩）
    val hitStartDeg: Float,   // 命中检测完整区间
    val hitEndDeg: Float
)

/**
 * 通用环形图（PRD §5.4 规范）：
 * donut 非实心饼、圆角分段 + 间隙、中心 KPI、选中段轻微加粗（约 +14%）。
 *
 * 性能设计：
 * - 弧度在 remember(slices) 里算好，数据引用不变不重算；
 * - 入场/数据变化动画 = 单个 Animatable，LaunchedEffect(arcs) 才重播；
 * - draw 阶段零对象分配，命中检测复用预计算的弧区间。
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    selectedKey: Any? = null,
    onSelect: ((Any?) -> Unit)? = null,
    gapDeg: Float = 3f,
    thicknessFraction: Float = 0.15f,
    selectedBoost: Float = 1.14f,
    animateOnDataChange: Boolean = true,
    centerContent: (@Composable () -> Unit)? = null
) {
    val emptyOutline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    // 1) 弧度预计算：slices 引用不变 → 不重算（ViewModel 保证切片列表 stateIn 缓存）
    val arcs = remember(slices, gapDeg) {
        val total = slices.sumOf { it.valueFen }.coerceAtLeast(1L)
        var acc = -90f // 12 点方向起
        slices.map { s ->
            val full = s.valueFen.toFloat() / total * 360f
            val spec = ArcSpec(
                key = s.key,
                color = s.color,
                startDeg = acc + gapDeg / 2f,
                sweepDeg = (full - gapDeg).coerceAtLeast(1f),
                hitStartDeg = acc,
                hitEndDeg = acc + full
            )
            acc += full
            spec
        }
    }

    // 2) 动画：只在「组件存活期间数据真的变了」才播；滚出列表被销毁后重组直接满值静显。
    //    否则 LazyColumn 滚动滑回时入场动画反复重播，Canvas 每帧重绘 → 肉眼可见的卡。
    val progress = remember { Animatable(1f) }
    var prevArcs by remember { mutableStateOf(arcs) }
    LaunchedEffect(arcs) {
        if (arcs !== prevArcs) {
            prevArcs = arcs
            if (animateOnDataChange) progress.snapTo(0f)
            progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (onSelect != null) {
                        Modifier.pointerInput(arcs, selectedKey, thicknessFraction) {
                            detectTapGestures { offset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val cx = w / 2f
                                val cy = h / 2f
                                val diameter = min(w, h)
                                val thickness = diameter * thicknessFraction
                                val outerR = diameter / 2f
                                val innerR = outerR - thickness
                                val dx = offset.x - cx
                                val dy = offset.y - cy
                                val r = sqrt(dx * dx + dy * dy)
                                if (r in innerR..outerR) {
                                    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    while (deg < -90f) deg += 360f
                                    while (deg >= 270f) deg -= 360f
                                    val hit = arcs.firstOrNull { deg >= it.hitStartDeg && deg < it.hitEndDeg }
                                    onSelect(if (hit == null || hit.key == selectedKey) null else hit.key)
                                }
                            }
                        }
                    } else Modifier
                )
        ) {
            val diameter = min(size.width, size.height)
            val thickness = diameter * thicknessFraction
            val arcSize = Size(diameter - thickness, diameter - thickness)
            val topLeft = Offset(
                (size.width - arcSize.width) / 2f,
                (size.height - arcSize.height) / 2f
            )
            if (arcs.isEmpty()) {
                drawArc(emptyOutline, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = thickness + 2.dp.toPx()))
                drawArc(Color.White, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = thickness))
            }
            val p = progress.value
            arcs.forEach { arc ->
                val boost = if (arc.key == selectedKey) selectedBoost else 1f
                drawArc(
                    color = arc.color,
                    startAngle = arc.startDeg,
                    sweepAngle = arc.sweepDeg * p,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = thickness * boost, cap = StrokeCap.Round)
                )
            }
        }
        centerContent?.invoke()
    }
}
