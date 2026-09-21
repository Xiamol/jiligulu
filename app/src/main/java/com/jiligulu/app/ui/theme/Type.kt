package com.jiligulu.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R

/** Bundled Chinese fonts keep text legible even when the device uses a decorative system font. */
@OptIn(ExperimentalTextApi::class)
val GuluTextFont = FontFamily(
    Font(R.font.noto_sans_sc, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_sans_sc, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.noto_sans_sc, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.noto_sans_sc, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

val GuluBrandFont = FontFamily(Font(R.font.zcool_kuaile))

private fun textStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = GuluTextFont,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum"
)

val GuluTypography = Typography(
    displayLarge = textStyle(44, 52, FontWeight.SemiBold),
    displayMedium = textStyle(36, 44, FontWeight.SemiBold),
    displaySmall = textStyle(32, 40, FontWeight.SemiBold),
    headlineLarge = textStyle(30, 38, FontWeight.SemiBold),
    headlineMedium = textStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = textStyle(24, 32, FontWeight.SemiBold),
    titleLarge = textStyle(20, 28, FontWeight.SemiBold),
    titleMedium = textStyle(16, 24, FontWeight.Medium),
    titleSmall = textStyle(14, 21, FontWeight.Medium),
    bodyLarge = textStyle(14, 22),
    bodyMedium = textStyle(13, 20),
    bodySmall = textStyle(12, 18),
    labelLarge = textStyle(14, 20, FontWeight.Medium),
    labelMedium = textStyle(12, 18),
    labelSmall = textStyle(11, 16)
)
