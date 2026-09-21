package com.jiligulu.app.domain.color

import androidx.compose.ui.graphics.Color

/**
 * 黄金角调色板 —— PRD §5.4 分类颜色策略第 1 板斧：
 * 分类创建时按顺序取色（hue = 15 + i × 137.5°），相邻颜色差异最大化，免手调。
 * 色值会固化进 Category.colorHue，这里只负责"分配第 i 个分类该用什么色"。
 */
object GoldenAnglePalette {

    private const val GOLDEN_ANGLE = 137.5f
    private const val BASE_HUE = 15f

    /** 第 index 个分类（从 0 开始）的色相 */
    fun hueFor(index: Int): Float = (BASE_HUE + index * GOLDEN_ANGLE) % 360f

    /**
     * 由色相反Compose颜色。饱和度/明度按主题固定，保证全 App 风格统一。
     * 分类超过 12 个后调用方把 useAltLightness 交替置 true，做明度错位复用（第 3 板斧）。
     */
    fun colorForHue(
        hue: Float,
        saturation: Float = 0.60f,
        useAltLightness: Boolean = false
    ): Color = Color.hsl(
        hue = hue,
        saturation = saturation,
        lightness = if (useAltLightness) 0.46f else 0.58f
    )

    fun colorForIndex(index: Int, saturation: Float = 0.60f): Color =
        colorForHue(hueFor(index), saturation, useAltLightness = index >= 12)
}
