package com.jiligulu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.domain.category.CategoryLabels

/** SVG/Builtin values are never shown as source text; old bubble placeholders use the same fallback. */
internal fun categoryBadgeGlyph(name: String, icon: String): String {
    val candidate = icon.trim()
    val points = candidate.codePoints().toArray()
    val hasEmoji = points.any { isEmojiBase(it) || it == 0x20E3 }
    val onlyEmoji = points.all { isEmojiBase(it) || it in 0xFE0E..0xFE0F || it == 0x200D || it == 0x20E3 ||
        it in 0xE0020..0xE007F || (0x20E3 in points && (it in 0x30..0x39 || it == 0x23 || it == 0x2A)) }
    if (hasEmoji && onlyEmoji && points.size <= 16 && candidate.replace("\uFE0F", "") != "🫧") return candidate
    val label = CategoryLabels.displayName(name.trim())
    return label.substring(0, label.offsetByCodePoints(0, 1))
}

private fun isEmojiBase(point: Int): Boolean = point in 0x1F000..0x1FAFF || point in 0x2600..0x27BF ||
    point in 0x2300..0x23FF || point in 0x2194..0x2199 || point in 0x21A9..0x21AA ||
    point in 0x2934..0x2935 || point in 0x2B05..0x2B07 || point in 0x2B1B..0x2B1C ||
    point in setOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2B50, 0x2B55, 0x3030, 0x303D, 0x3297, 0x3299)

@Composable
fun CategoryBadge(
    name: String,
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier.size(size).background(tint.copy(alpha = 0.12f), CircleShape)
            .clearAndSetSemantics { contentDescription = "${CategoryLabels.displayName(name)}分类图标" },
        contentAlignment = Alignment.Center
    ) {
        Text(categoryBadgeGlyph(name, icon), fontSize = (size.value * 0.53f).sp,
            fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
    }
}
