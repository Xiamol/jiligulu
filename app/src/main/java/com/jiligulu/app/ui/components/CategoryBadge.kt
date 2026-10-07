package com.jiligulu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val keycap = 0x20E3 in points
    val hasEmoji = points.any { isEmojiBase(it) || it == 0x20E3 }
    val onlyEmoji = points.all { isEmojiBase(it) || it in 0xFE0E..0xFE0F || it == 0x200D || it == 0x20E3 ||
        it in 0xE0020..0xE007F || (keycap && (it in 0x30..0x39 || it == 0x23 || it == 0x2A)) }
    if (hasEmoji && onlyEmoji && points.size <= 16 && candidate.replace("\uFE0F", "") != "🫧") return candidate
    val label = CategoryLabels.displayName(name.trim())
    return label.substring(0, label.offsetByCodePoints(0, 1))
}

private fun isEmojiBase(point: Int): Boolean = point in 0x1F000..0x1FAFF || point in 0x2600..0x27BF ||
    point in 0x2300..0x23FF || point in 0x2194..0x2199 || point in 0x21A9..0x21AA ||
    point in 0x2934..0x2935 || point in 0x2B05..0x2B07 || point in 0x2B1B..0x2B1C ||
    when (point) {
        0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2B50, 0x2B55, 0x3030, 0x303D, 0x3297, 0x3299 -> true
        else -> false
    }

@Composable
fun CategoryBadge(
    name: String,
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val displayName = remember(name) { CategoryLabels.displayName(name) }
    val iconTint = when (displayName) {
        "吃饭", "餐饮" -> Color(0xFFEF8D87)
        "交通" -> Color(0xFF69B89A)
        "零食" -> Color(0xFFEAB567)
        "饮品" -> Color(0xFFDB92B8)
        "水果", "蔬菜" -> Color(0xFF7BAD83)
        else -> tint
    }
    val curated = remember(displayName) { curatedCategoryIcon(displayName) }
    val glyph = remember(name, icon) { categoryBadgeGlyph(name, icon) }
    Box(
        modifier.size(size).background(iconTint.copy(alpha = 0.12f), CircleShape)
            .clearAndSetSemantics { contentDescription = "${displayName}分类图标" },
        contentAlignment = Alignment.Center
    ) {
        if (curated != null) Icon(curated, contentDescription = null, tint = iconTint,
            modifier = Modifier.size(size * .57f))
        else Text(glyph, fontSize = (size.value * 0.53f).sp,
            fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
    }
}

/** Fixed vector silhouettes make familiar categories consistent across every Android font. */
private fun curatedCategoryIcon(name: String): ImageVector? = when (name) {
    "吃饭", "餐饮" -> Icons.Outlined.Restaurant
    "饮品" -> Icons.Outlined.LocalCafe
    "零食" -> Icons.Outlined.Cookie
    "交通" -> Icons.Outlined.DirectionsBus
    "购物" -> Icons.Outlined.ShoppingBag
    "日用品" -> Icons.Outlined.LocalMall
    "住房" -> Icons.Outlined.Home
    "数码" -> Icons.Outlined.Devices
    "学习" -> Icons.Outlined.School
    "医疗" -> Icons.Outlined.MedicalServices
    "娱乐" -> Icons.Outlined.SportsEsports
    "生活服务" -> Icons.Outlined.WorkOutline
    "宠物" -> Icons.Outlined.Pets
    "水果" -> FruitCategoryIcon
    "蔬菜" -> Icons.Outlined.Grass
    "通讯" -> Icons.Outlined.PhoneAndroid
    "运动" -> Icons.Outlined.FitnessCenter
    "旅行" -> Icons.Outlined.Luggage
    "礼物" -> Icons.Outlined.CardGiftcard
    "工资" -> Icons.Outlined.AccountBalanceWallet
    "生活费" -> Icons.Outlined.Savings
    "红包", "人情" -> Icons.Outlined.CardGiftcard
    "转账" -> Icons.Outlined.SwapHoriz
    com.jiligulu.app.domain.category.CategoryDefaults.VACUUM_NAME -> Icons.Outlined.Inbox
    else -> null
}

/** A small apple silhouette stays consistent instead of relying on vendor emoji rendering. */
private val FruitCategoryIcon: ImageVector by lazy {
    ImageVector.Builder("Fruit", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 8f)
            curveTo(9.5f, 5.7f, 4f, 6.4f, 4f, 11.3f)
            curveTo(4f, 16.3f, 6.6f, 21f, 9.3f, 21f)
            curveTo(10.7f, 21f, 11.1f, 20f, 12f, 20f)
            curveTo(12.9f, 20f, 13.3f, 21f, 14.7f, 21f)
            curveTo(17.4f, 21f, 20f, 16.3f, 20f, 11.3f)
            curveTo(20f, 6.4f, 14.5f, 5.7f, 12f, 8f)
            close()
            moveTo(12f, 6f)
            curveTo(12f, 3.5f, 14f, 2f, 17f, 2f)
            curveTo(17f, 4.6f, 14.7f, 6f, 12f, 6f)
            close()
            moveTo(12f, 8f)
            curveTo(11.8f, 6.8f, 11.4f, 5.2f, 10.6f, 4.4f)
        }
    }.build()
}
