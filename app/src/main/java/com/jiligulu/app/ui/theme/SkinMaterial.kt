package com.jiligulu.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiligulu.app.data.prefs.LittleWorldSkin

/** Material, silhouette and printed details travel with the saved skin on every page. */
@Immutable
data class SkinMaterial(
    val skin: LittleWorldSkin,
    val dark: Boolean,
    val page: Color,
    val paper: Color,
    val raisedPaper: Color,
    val note: Color,
    val ink: Color,
    val secondaryInk: Color,
    val accent: Color,
    val onAccent: Color,
    val border: Color,
    val texture: Color,
    val sheen: Color,
    val shapes: Shapes,
    val borderWidth: Dp,
    val description: String
)

val LocalSkinMaterial = staticCompositionLocalOf { skinMaterialFor(LittleWorldSkin.DEFAULT) }

/** The picker uses the exact same tokens as the installed skin. */
fun skinMaterialFor(skin: LittleWorldSkin, dark: Boolean = false): SkinMaterial {
    val light = when (skin) {
        LittleWorldSkin.MOONLIGHT -> SkinMaterial(
            skin, false, Color(0xFFF0EEF9), Color(0xFFF9F7FF), Color(0xFFECE9F9), Color(0xFFEDE7FA),
            Color(0xFF36324D), Color(0xFF78718F), Color(0xFF7663AD), Color.White,
            Color(0xFFC3B4DE), Color(0xFF9688BA), Color(0xFFFFFFFF),
            Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(34.dp)),
            1.dp, "珠光玻璃 · 星轨细纹"
        )
        LittleWorldSkin.SCRAPBOOK -> SkinMaterial(
            skin, false, Color(0xFFFAF5EA), Color(0xFFFFFCF3), Color(0xFFF0E8D9), Color(0xFFFFF2D6),
            Color(0xFF423A42), Color(0xFF85776F), ActionPurple, Color.White,
            Color(0xFFCABEAA), Color(0xFFB0A188), Color(0xFFFFFEF8),
            Shapes(small = RoundedCornerShape(5.dp), medium = RoundedCornerShape(12.dp, 4.dp, 12.dp, 4.dp),
                large = RoundedCornerShape(18.dp, 5.dp, 18.dp, 5.dp), extraLarge = RoundedCornerShape(22.dp, 8.dp, 22.dp, 8.dp)),
            1.dp, "拼贴纸页 · 手账格纹"
        )
        LittleWorldSkin.STRAWBERRY -> SkinMaterial(
            skin, false, Color(0xFFFFF0F0), Color(0xFFFFFAF6), Color(0xFFFCE2E5), Color(0xFFFFE8E8),
            Color(0xFF563841), Color(0xFF94717C), Color(0xFFB65E7B), Color.White,
            Color(0xFFE8B8C0), Color(0xFFD697A9), Color(0xFFFFFDF5),
            Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp, 28.dp, 13.dp, 28.dp), extraLarge = RoundedCornerShape(32.dp, 32.dp, 18.dp, 32.dp)),
            1.5.dp, "奶油花边 · 草莓格纹"
        )
        LittleWorldSkin.WOODLAND -> SkinMaterial(
            skin, false, Color(0xFFF1F3E8), Color(0xFFFCFAEF), Color(0xFFE5EBD8), Color(0xFFF0F1DC),
            Color(0xFF36493C), Color(0xFF778474), Color(0xFF4D7D63), Color.White,
            Color(0xFFB3C2A2), Color(0xFF8C9F79), Color(0xFFFFFFF3),
            Shapes(small = CutCornerShape(4.dp), medium = CutCornerShape(8.dp, 3.dp, 8.dp, 3.dp),
                large = CutCornerShape(13.dp, 4.dp, 13.dp, 4.dp), extraLarge = CutCornerShape(18.dp, 6.dp, 18.dp, 6.dp)),
            1.dp, "牛皮信笺 · 森林邮戳"
        )
    }
    if (!dark) return light
    val night = when (skin) {
        LittleWorldSkin.MOONLIGHT -> Color(0xFF201F31)
        LittleWorldSkin.SCRAPBOOK -> Color(0xFF29241F)
        LittleWorldSkin.STRAWBERRY -> Color(0xFF302227)
        LittleWorldSkin.WOODLAND -> Color(0xFF202A24)
    }
    return light.copy(
        dark = true,
        page = night,
        paper = lerp(night, light.paper, .065f),
        raisedPaper = lerp(night, light.raisedPaper, .12f),
        note = lerp(night, light.note, .14f),
        ink = lerp(light.paper, Color.White, .25f),
        secondaryInk = lerp(night, light.paper, .61f),
        accent = lerp(light.accent, light.paper, .48f),
        onAccent = night,
        border = lerp(night, light.border, .38f),
        texture = lerp(night, light.texture, .48f),
        sheen = lerp(night, light.sheen, .18f)
    )
}

/** Use on a card's content layer; cached brushes, outline and paper printing never animate. */
@Composable
fun Modifier.skinPaperSurface(
    material: SkinMaterial = LocalSkinMaterial.current,
    shape: Shape = material.shapes.large,
    border: Boolean = true
): Modifier = clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val wash = Brush.linearGradient(
        listOf(material.sheen, material.paper, material.paper, material.raisedPaper),
        start = Offset.Zero, end = Offset(size.width, size.height * 1.3f)
    )
    val stroke = Stroke(material.borderWidth.toPx(), pathEffect = if (material.skin == LittleWorldSkin.WOODLAND)
        PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null)
    val printing = createSkinPrinting(material, scene = false, size = size, unit = 1.dp.toPx())
    onDrawWithContent {
        drawRect(wash)
        drawSkinPrinting(printing)
        drawContent()
        if (border) drawOutline(outline, material.border.copy(alpha = .88f), style = stroke)
    }
}

/** Background companion to skinPaperSurface; also usable behind an existing page Scaffold. */
@Composable
fun Modifier.skinSceneSurface(material: SkinMaterial = LocalSkinMaterial.current): Modifier = drawWithCache {
    val wash = Brush.linearGradient(
        listOf(material.page, lerp(material.page, material.raisedPaper, .32f), material.page),
        Offset.Zero, Offset(size.width, size.height)
    )
    val printing = createSkinPrinting(material, scene = true, size = size, unit = 1.dp.toPx())
    onDrawBehind {
        drawRect(wash)
        drawSkinPrinting(printing)
    }
}

private data class SkinPrintFill(val path: Path, val color: Color)
private data class SkinPrintStroke(val path: Path, val color: Color, val stroke: Stroke)
private data class SkinPrinting(val fills: List<SkinPrintFill>, val strokes: List<SkinPrintStroke>)

/** Constructed only when size, density or skin changes, including grain and decorative paths. */
private fun createSkinPrinting(material: SkinMaterial, scene: Boolean, size: Size, unit: Float): SkinPrinting {
    val fills = mutableListOf<SkinPrintFill>()
    val strokes = mutableListOf<SkinPrintStroke>()
    val densityAlpha = if (material.dark) .7f else 1f
    val fiber = material.texture.copy(alpha = (if (scene) .065f else .075f) * densityAlpha)
    val grainStep = 18f * unit
    val grain = Path()
    var row = 0
    var y = 4f * unit
    while (y < size.height) {
        var col = 0
        var x = 5f * unit
        while (x < size.width) {
            val jitter = ((row * 17 + col * 11) % 13) * unit / 3f
            grain.addOval(Rect(center = Offset(x + jitter, y + jitter * .7f), radius = .45f * unit))
            x += grainStep
            col++
        }
        y += grainStep
        row++
    }
    fills += SkinPrintFill(grain, fiber)
    when (material.skin) {
        LittleWorldSkin.MOONLIGHT -> {
            val starInk = material.texture.copy(alpha = if (scene) .19f else .15f)
            val stars = Path()
            val step = if (scene) 78f * unit else 58f * unit
            var sy = 22f * unit
            var index = 0
            while (sy < size.height) {
                val sx = if (index % 2 == 0) 18f * unit else size.width - 19f * unit
                stars.addTinyStar(Offset(sx, sy), (if (index % 3 == 0) 3f else 1.8f) * unit)
                sy += step
                index++
            }
            fills += SkinPrintFill(stars, starInk)
            strokes += SkinPrintStroke(Path().apply {
                moveTo(size.width * .6f, 0f); lineTo(size.width * .15f, size.height)
            }, material.sheen.copy(alpha = .55f), Stroke(13f * unit))
            if (!scene && size.width > 10f * unit && size.height > 10f * unit) {
                strokes += SkinPrintStroke(Path().apply {
                    addRoundRect(RoundRect(Rect(5f * unit, 5f * unit, size.width - 5f * unit, size.height - 5f * unit), CornerRadius(23f * unit)))
                }, material.sheen.copy(alpha = .7f), Stroke(.8f * unit))
            }
        }
        LittleWorldSkin.SCRAPBOOK -> {
            val gridInk = material.texture.copy(alpha = if (scene) .105f else .095f)
            val grid = 24f * unit
            val gridLines = Path()
            var gx = 0f
            while (gx < size.width) {
                gridLines.moveTo(gx, 0f); gridLines.lineTo(gx, size.height)
                gx += grid
            }
            var gy = 0f
            while (gy < size.height) {
                gridLines.moveTo(0f, gy); gridLines.lineTo(size.width, gy)
                gy += grid
            }
            strokes += SkinPrintStroke(gridLines, gridInk, Stroke(.5f * unit))
            if (!scene) strokes += SkinPrintStroke(Path().apply {
                moveTo(9f * unit, 8f * unit); lineTo(9f * unit, size.height - 8f * unit)
            }, material.border.copy(alpha = .42f), Stroke(.7f * unit,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * unit, 4f * unit))))
        }
        LittleWorldSkin.STRAWBERRY -> {
            val square = 14f * unit
            val gingham = material.texture.copy(alpha = if (scene) .055f else .04f)
            val verticalChecks = Path()
            val horizontalChecks = Path()
            var gx = 0f
            while (gx < size.width) {
                verticalChecks.addRect(Rect(gx, 0f, gx + square, size.height))
                gx += square * 2f
            }
            var gy = 0f
            while (gy < size.height) {
                horizontalChecks.addRect(Rect(0f, gy, size.width, gy + square))
                gy += square * 2f
            }
            fills += SkinPrintFill(verticalChecks, gingham)
            fills += SkinPrintFill(horizontalChecks, gingham)
            if (!scene) {
                val piping = material.border.copy(alpha = .58f)
                val dots = Path()
                val edge = 5f * unit
                var px = 27f * unit
                while (px < size.width - 27f * unit) {
                    dots.addOval(Rect(Offset(px, edge), 1.65f * unit))
                    dots.addOval(Rect(Offset(px, size.height - edge), 1.65f * unit))
                    px += 7f * unit
                }
                var py = 27f * unit
                while (py < size.height - 27f * unit) {
                    dots.addOval(Rect(Offset(edge, py), 1.65f * unit))
                    dots.addOval(Rect(Offset(size.width - edge, py), 1.65f * unit))
                    py += 7f * unit
                }
                fills += SkinPrintFill(dots, piping)
            }
        }
        LittleWorldSkin.WOODLAND -> {
            val laid = material.texture.copy(alpha = if (scene) .09f else .075f)
            val paperLines = Path()
            var gy = 2f * unit
            while (gy < size.height) {
                paperLines.moveTo(0f, gy); paperLines.lineTo(size.width, gy)
                gy += 7f * unit
            }
            strokes += SkinPrintStroke(paperLines, laid, Stroke(.6f * unit))
            val sealInk = material.texture.copy(alpha = if (scene) .12f else .13f)
            val center = Offset(size.width - 21f * unit, size.height - 20f * unit)
            val seal = Path().apply {
                addOval(Rect(center, 13f * unit))
                addOval(Rect(center, 10f * unit))
                for (line in 0..2) {
                    moveTo(center.x - 24f * unit, center.y + (line - 1) * 4f * unit)
                    lineTo(center.x + 18f * unit, center.y + (line - 1) * 4f * unit)
                }
            }
            strokes += SkinPrintStroke(seal, sealInk, Stroke(.7f * unit))
        }
    }
    return SkinPrinting(fills, strokes)
}

private fun DrawScope.drawSkinPrinting(printing: SkinPrinting) {
    printing.fills.forEach { drawPath(it.path, it.color) }
    printing.strokes.forEach { drawPath(it.path, it.color, style = it.stroke) }
}

private fun Path.addTinyStar(center: Offset, radius: Float) {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius * .28f, center.y - radius * .28f)
    lineTo(center.x + radius, center.y)
    lineTo(center.x + radius * .28f, center.y + radius * .28f)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius * .28f, center.y + radius * .28f)
    lineTo(center.x - radius, center.y)
    lineTo(center.x - radius * .28f, center.y - radius * .28f)
    close()
}
