package com.jiligulu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.jiligulu.app.data.prefs.LittleWorldSkin

val LocalGuluSkin = staticCompositionLocalOf { LittleWorldSkin.DEFAULT }

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
 * 全局唯一主题入口：颜色、纸张材质和组件轮廓共同随已保存的皮肤切换。
 */
@Composable
fun GuluTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: LittleWorldSkin = LittleWorldSkin.DEFAULT,
    content: @Composable () -> Unit
) {
    val material = remember(skin, darkTheme) { skinMaterialFor(skin, darkTheme) }
    val accent = material.accent
    val base = if(darkTheme) DarkColors else LightColors
    val scheme = base.copy(primary = accent, onPrimary = material.onAccent, secondary = accent,
        onSecondary = material.onAccent, surfaceTint = accent,
        primaryContainer = accent.copy(alpha = if (darkTheme) .18f else .1f).compositeOver(material.paper),
        onPrimaryContainer = if (darkTheme) material.ink else accent,
        secondaryContainer = material.raisedPaper,
        onSecondaryContainer = material.ink,
        background = material.page, onBackground = material.ink,
        surface = material.paper, onSurface = material.ink,
        surfaceVariant = material.raisedPaper, onSurfaceVariant = material.secondaryInk,
        surfaceContainer = material.paper, surfaceContainerHigh = material.raisedPaper,
        surfaceContainerHighest = material.raisedPaper,
        surfaceContainerLow = material.paper, surfaceContainerLowest = material.page,
        outline = material.border, outlineVariant = material.border.copy(alpha = .55f),
        tertiaryContainer = material.note, onTertiaryContainer = material.ink)
    CompositionLocalProvider(LocalGuluSkin provides skin, LocalSkinMaterial provides material) { MaterialTheme(
        colorScheme = scheme,
        typography = GuluTypography,
        shapes = material.shapes,
        content = content
    ) }
}
