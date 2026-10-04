package com.jiligulu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * 全局唯一主题入口。换皮 = 改 Color.kt 的 token，业务页面零改动。
 */
@Composable
fun GuluTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: LittleWorldSkin = LittleWorldSkin.DEFAULT,
    content: @Composable () -> Unit
) {
    val accent = when(skin) {
        LittleWorldSkin.MOONLIGHT -> if(darkTheme) Color(0xFFC4B6EF) else Color(0xFF7760AC)
        LittleWorldSkin.SCRAPBOOK -> if(darkTheme) GuluPurple else ActionPurple
        LittleWorldSkin.STRAWBERRY -> if(darkTheme) Color(0xFFF2B8CB) else Color(0xFFB95D7C)
        LittleWorldSkin.WOODLAND -> if(darkTheme) Color(0xFFAAD8BA) else Color(0xFF477D63)
    }
    val paper = when(skin) {
        LittleWorldSkin.MOONLIGHT -> Color(0xFFF4F1FC)
        LittleWorldSkin.SCRAPBOOK -> BgLight
        LittleWorldSkin.STRAWBERRY -> Color(0xFFFFF6F5)
        LittleWorldSkin.WOODLAND -> Color(0xFFF5F8F1)
    }
    val base = if(darkTheme) DarkColors else LightColors
    val scheme = base.copy(primary = accent, secondary = accent,
        primaryContainer = if(darkTheme) accent.copy(alpha=.18f).compositeOver(SurfaceDark) else accent.copy(alpha=.1f).compositeOver(paper),
        onPrimaryContainer = if(darkTheme) OnBgDark else accent,
        secondaryContainer = if(darkTheme) SurfaceVariantDark else accent.copy(alpha=.065f).compositeOver(paper),
        onSecondaryContainer = if(darkTheme) OnBgDark else accent,
        background = if(darkTheme) BgDark else paper,
        tertiaryContainer = if(darkTheme) PaperNoteDark else paper)
    CompositionLocalProvider(LocalGuluSkin provides skin) { MaterialTheme(
        colorScheme = scheme,
        typography = GuluTypography,
        shapes = GuluShapes,
        content = content
    ) }
}
