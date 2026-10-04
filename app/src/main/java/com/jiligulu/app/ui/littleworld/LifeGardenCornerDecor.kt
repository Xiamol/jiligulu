package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A few original translucent blossoms and star glints, drawn behind the paper content. */
@Composable
internal fun LifeGardenCornerDecor(modifier: Modifier = Modifier) {
    Box(modifier.drawWithCache {
        val u = 1.dp.toPx()
        val petals = ArrayList<GardenPetal>(30)
        val marks = ArrayList<GardenMark>(16)
        val dots = ArrayList<GardenDot>(48)
        val stems = ArrayList<Path>(2)
        val sage = Color(0xFF9DAF9D).copy(alpha = .28f)
        val pink = Color(0xFFDAB1CA)
        val lavender = Color(0xFFB5A2D5)
        val mint = Color(0xFFB7D4BF)
        val pearl = Color(0xFFFFFDF5)
        val fineStroke = Stroke(.6f * u, cap = StrokeCap.Round)

        fun oval(center: Offset, major: Float, minor: Float, angle: Float): Path {
            val ux = Offset(cos(angle) * major, sin(angle) * major)
            val vy = Offset(-sin(angle) * minor, cos(angle) * minor)
            val k = .5522848f
            fun Path.curve(a: Offset, b: Offset, end: Offset) = cubicTo(a.x, a.y, b.x, b.y, end.x, end.y)
            return Path().apply {
                val start = center + ux; moveTo(start.x, start.y)
                curve(center + ux + vy * k, center + vy + ux * k, center + vy)
                curve(center + vy - ux * k, center - ux + vy * k, center - ux)
                curve(center - ux - vy * k, center - vy - ux * k, center - vy)
                curve(center - vy + ux * k, center + ux - vy * k, center + ux)
                close()
            }
        }
        fun flower(x: Float, y: Float, radius: Float, rotation: Float, first: Color, second: Color) {
            val center = Offset(x, y)
            repeat(5) { i ->
                val angle = ((rotation + i * 72f) * PI / 180).toFloat()
                val axis = Offset(cos(angle), sin(angle))
                val petalCenter = center + axis * (radius * .45f)
                val outline = oval(petalCenter, radius * .54f, radius * .38f, angle)
                petals.add(GardenPetal(outline, Brush.linearGradient(
                    listOf(first.copy(alpha = .29f), second.copy(alpha = .40f)),
                    start = petalCenter - axis * radius * .5f, end = petalCenter + axis * radius * .5f)))
            }
            dots.add(GardenDot(center, radius * .14f, Color(0xFFD5BE83).copy(alpha = .40f)))
            repeat(5) { i ->
                val angle = ((i * 72f - 12f) * PI / 180).toFloat()
                dots.add(GardenDot(center + Offset(cos(angle), sin(angle)) * (radius * .24f),
                    .3f * u, Color(0xFFD1B879).copy(alpha = .35f)))
            }
        }
        // One nearly complete 17dp bloom fits inside each outer margin. Other blooms are
        // smaller and farther apart; this is a corner accent, not a repeating floral frame.
        flower(8.9f * u, 18f * u, 8.5f * u, -16f, pearl, pink)
        flower(11f * u, min(90f * u, size.height * .22f), 6.7f * u, 11f, lavender, pink)
        flower(8f * u, min(275f * u, size.height * .46f), 5.5f * u, -9f, pearl, mint)
        flower(size.width - 8.9f * u, 31f * u, 8.5f * u, 14f, pink, lavender)
        flower(size.width - 12f * u, min(112f * u, size.height * .26f), 6.6f * u, -11f, pearl, mint)
        flower(size.width - 8f * u, min(288f * u, size.height * .51f), 5.5f * u, 21f, lavender, pink)

        stems.add(Path().apply {
            moveTo(6f * u, 31f * u)
            cubicTo(14f * u, 48f * u, 4f * u, 67f * u, 11f * u, 82f * u)
        })
        stems.add(Path().apply {
            moveTo(size.width - 10f * u, 43f * u)
            cubicTo(size.width - 4f * u, 59f * u, size.width - 17f * u, 76f * u, size.width - 12f * u, 104f * u)
        })
        marks.add(GardenMark(oval(Offset(12f * u, 52f * u), 4.1f * u, 1.8f * u, -.7f), sage))
        marks.add(GardenMark(oval(Offset(size.width - 14f * u, 73f * u), 3.8f * u, 1.7f * u, .7f), sage))
        // A tiny, rounded butterfly and four quiet glints keep the botanical accents playful.
        marks.add(GardenMark(oval(Offset(7.5f * u, 146f * u), 2.8f * u, 1.9f * u, -.7f), lavender.copy(alpha = .28f)))
        marks.add(GardenMark(oval(Offset(12f * u, 146f * u), 2.8f * u, 1.9f * u, .7f), pink.copy(alpha = .28f)))
        fun glint(x: Float, y: Float, radius: Float) {
            marks.add(GardenMark(Path().apply {
                moveTo(x, y - radius)
                cubicTo(x + radius * .15f, y - radius * .3f, x + radius * .3f, y - radius * .15f, x + radius, y)
                cubicTo(x + radius * .3f, y + radius * .15f, x + radius * .15f, y + radius * .3f, x, y + radius)
                cubicTo(x - radius * .15f, y + radius * .3f, x - radius * .3f, y + radius * .15f, x - radius, y)
                cubicTo(x - radius * .3f, y - radius * .15f, x - radius * .15f, y - radius * .3f, x, y - radius); close()
            }, lavender.copy(alpha = .24f)))
        }
        glint(14f * u, 193f * u, 2.7f * u)
        glint(size.width - 10f * u, 177f * u, 2.6f * u)
        glint(10f * u, size.height - 19f * u, 2.5f * u)
        glint(size.width - 12f * u, size.height - 26f * u, 2.8f * u)

        onDrawBehind {
            repeat(stems.size) { drawPath(stems[it], sage, style = fineStroke) }
            repeat(marks.size) { val mark = marks[it]; drawPath(mark.path, mark.color) }
            repeat(petals.size) { val petal = petals[it]; drawPath(petal.path, petal.brush) }
            repeat(dots.size) { val dot = dots[it]; drawCircle(dot.color, dot.radius, dot.center) }
        }
    })
}

private data class GardenPetal(val path: Path, val brush: Brush)
private data class GardenMark(val path: Path, val color: Color)
private data class GardenDot(val center: Offset, val radius: Float, val color: Color)
