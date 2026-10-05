package com.jiligulu.app.ui.littleworld

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlin.math.cos
import kotlin.math.sin

enum class SecretEntrance { PULL, LOGO }

/** Finite transition above the current page. The caller navigates only after onFinished. */
@Composable
fun SecretEntranceOverlay(source: SecretEntrance, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember(source) { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(source) {
        progress.animateTo(1f, tween(if (source == SecretEntrance.PULL) 1250 else 1450, easing = FastOutSlowInEasing))
        finish()
    }
    Box(modifier.fillMaxSize().zIndex(30f).semantics {
        contentDescription = if (source == SecretEntrance.PULL) "秘密小门正在打开" else "阿噜的魔法印记正在亮起"
    }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        Canvas(Modifier.matchParentSize()) {
            val p = progress.value
            val warm = Color(0xFFFFECD4)
            if (source == SecretEntrance.PULL) {
                val reveal = ((p - .18f) / .70f).coerceIn(0f, 1f)
                drawRect(Brush.radialGradient(listOf(warm, Color(0xFFA99ED0)), center,
                    size.maxDimension * .75f), alpha = (.35f + p).coerceAtMost(1f))
                val panelWidth = size.width * .51f
                val slide = reveal * panelWidth
                repeat(2) { side ->
                    val x = if (side == 0) -slide else size.width / 2 + slide
                    val bounds = Size(panelWidth, size.height)
                    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF956F50), Color(0xFFD6B586), Color(0xFFAD875F)),
                        startX = x, endX = x + panelWidth), Offset(x, 0f), bounds, CornerRadius(3.dp.toPx()))
                    repeat(5) { plank ->
                        val grainX = x + panelWidth * (plank + .5f) / 5
                        drawLine(Color(0xFF795937).copy(alpha = .24f), Offset(grainX, 0f), Offset(grainX, size.height), 2.dp.toPx())
                        drawLine(Color(0xFFFFE0AE).copy(alpha = .23f), Offset(grainX + 4.dp.toPx(), 0f),
                            Offset(grainX + 4.dp.toPx(), size.height), 1.dp.toPx())
                    }
                    val handle = Offset(if (side == 0) x + panelWidth * .83f else x + panelWidth * .17f, size.height * .55f)
                    drawCircle(Color(0xFF725435), 18.dp.toPx(), handle + Offset(0f, 2.dp.toPx()))
                    drawCircle(Color(0xFFE9C879), 16.dp.toPx(), handle)
                    drawCircle(Color(0xFFB49357), 9.dp.toPx(), handle, style = Stroke(3.dp.toPx()))
                }
                val seam = (1f - reveal) * .8f
                drawLine(warm.copy(alpha = seam), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 3.dp.toPx())
                if (reveal > .45f) drawRect(warm, alpha = ((reveal - .45f) / .55f).coerceIn(0f, 1f) * .55f)
            } else {
                drawRect(Color(0xFF3D3259), alpha = (.12f + p * .69f).coerceAtMost(.8f))
                val radius = size.minDimension * (.13f + p * .16f)
                val alpha = (p * 5).coerceAtMost(1f)
                val sealCenter = Offset(size.width / 2, size.height * .45f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFECB7).copy(alpha = .62f), Color.Transparent),
                    sealCenter, radius * 1.55f), radius * 1.55f, sealCenter)
                drawCircle(Color(0xFFFFEABC).copy(alpha = alpha), radius, sealCenter, style = Stroke(2.dp.toPx()))
                drawCircle(Color(0xFFD5C9F6).copy(alpha = alpha), radius * .78f, sealCenter, style = Stroke(1.dp.toPx()))
                val star = Path()
                repeat(10) { point ->
                    val angle = -Math.PI / 2 + point * Math.PI / 5 + p * .35f
                    val r = radius * if (point % 2 == 0) .61f else .29f
                    val x = sealCenter.x + cos(angle).toFloat() * r
                    val y = sealCenter.y + sin(angle).toFloat() * r
                    if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close(); drawPath(star, Color(0xFFFFEAB8).copy(alpha = alpha), style = Stroke(3.dp.toPx()))
                repeat(12) { i ->
                    val angle = i * Math.PI / 6 + p * .8f
                    val orbit = radius * (1.15f + (i % 3) * .20f)
                    val point = sealCenter + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * orbit
                    drawLine(Color(0xFFFFF0CD).copy(alpha = alpha), point - Offset(4.dp.toPx(), 0f), point + Offset(4.dp.toPx(), 0f), 1.5.dp.toPx())
                    drawLine(Color(0xFFFFF0CD).copy(alpha = alpha), point - Offset(0f, 4.dp.toPx()), point + Offset(0f, 4.dp.toPx()), 1.5.dp.toPx())
                }
                val bloom = ((p - .72f) / .28f).coerceIn(0f, 1f)
                if (bloom > 0f) drawCircle(warm.copy(alpha = bloom), size.maxDimension * bloom, sealCenter)
            }
        }
        Text(if (source == SecretEntrance.PULL) "小门开啦，阿噜给你留了位置 ♡" else "印记亮起来了，跟着星光进去吧 ♡",
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
            fontFamily = GuluBrandFont, fontSize = 17.sp,
            color = if (source == SecretEntrance.PULL) Color(0xFF674C39) else Color(0xFFFFF1DB))
    }
}
