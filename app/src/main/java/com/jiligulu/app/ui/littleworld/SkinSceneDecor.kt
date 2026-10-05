package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.jiligulu.app.data.prefs.LittleWorldSkin
import com.jiligulu.app.ui.theme.LocalSkinMaterial
import com.jiligulu.app.ui.theme.SkinMaterial
import com.jiligulu.app.ui.theme.skinSceneSurface

/**
 * Place first inside a page-shell Box. It paints a quiet paper background and transparent
 * atlas cutouts at the edges; it owns no pointer input, animation or accessibility nodes.
 * Existing immersive scenes can keep their scene image with paintBackground = false.
 */
@Composable
fun SkinSceneDecor(
    modifier: Modifier = Modifier,
    material: SkinMaterial = LocalSkinMaterial.current,
    paintBackground: Boolean = true,
    showStickers: Boolean = true
) {
    Box(modifier.fillMaxSize().clipToBounds().clearAndSetSemantics {}
        .then(if (paintBackground) Modifier.skinSceneSurface(material) else Modifier)) {
        if (showStickers) {
            val rotation = when (material.skin) {
                LittleWorldSkin.MOONLIGHT -> 7f
                LittleWorldSkin.SCRAPBOOK -> -7f
                LittleWorldSkin.STRAWBERRY -> 4f
                LittleWorldSkin.WOODLAND -> -5f
            }
            LittleWorldSkinSticker(material.skin, SkinStickerPart.TAB,
                Modifier.size(if(paintBackground) 108.dp else 65.dp, if(paintBackground) 94.dp else 57.dp).align(Alignment.TopEnd).offset(x = 24.dp, y = 15.dp)
                    .rotate(rotation).graphicsLayer(alpha = if (material.dark) .2f else if(paintBackground) .3f else .14f))
            LittleWorldSkinSticker(material.skin, SkinStickerPart.KEEPSAKE,
                Modifier.size(if(paintBackground) 114.dp else 74.dp, if(paintBackground) 96.dp else 62.dp).align(Alignment.BottomStart).offset(x = (-30).dp, y = (-18).dp)
                    .rotate(-rotation).graphicsLayer(alpha = if (material.dark) .16f else if(paintBackground) .25f else .12f))
        }
    }
}
