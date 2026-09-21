package com.jiligulu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 深色 · 深夜手账 */
private val DarkColors = darkColorScheme(
    primary = GuluPurple,
    onPrimary = OnPurpleDark,
    primaryContainer = GuluPurpleSoftDark,
    onPrimaryContainer = OnBgDark,
    secondary = GuluPurple,
    secondaryContainer = CompanionSurfaceDark,
    onSecondaryContainer = OnBgDark,
    tertiaryContainer = PaperNoteDark,
    onTertiaryContainer = PaperInkDark,
    background = BgDark,
    onBackground = OnBgDark,
    surface = SurfaceDark,
    onSurface = OnBgDark,
    surfaceVariant = SurfaceVariantDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceSecondaryDark,
    outline = OutlineDark,
    outlineVariant = SurfaceVariantDark,
    error = DangerRed
)

/** 浅色 · 奶油便签（默认） */
private val LightColors = lightColorScheme(
    primary = ActionPurple,
    onPrimary = Color.White,
    primaryContainer = GuluPurpleSoft,
    onPrimaryContainer = GuluPurpleDeep,
    secondary = GuluPurpleDeep,
    secondaryContainer = CompanionSurfaceLight,
    onSecondaryContainer = GuluPurpleDeep,
    tertiaryContainer = PaperNoteLight,
    onTertiaryContainer = PaperInkLight,
    background = BgLight,
    onBackground = OnBgLight,
    surface = SurfaceLight,
    onSurface = OnBgLight,
    surfaceVariant = SurfaceVariantLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceSecondaryLight,
    outline = OutlineLight,
    outlineVariant = SurfaceVariantLight,
    error = DangerRed
)

/**
 * 全局唯一主题入口。换皮 = 改 Color.kt 的 token，业务页面零改动。
 */
@Composable
fun GuluTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GuluTypography,
        shapes = GuluShapes,
        content = content
    )
}
