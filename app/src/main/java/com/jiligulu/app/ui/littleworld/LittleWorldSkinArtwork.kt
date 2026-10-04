package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.jiligulu.app.R
import com.jiligulu.app.data.prefs.LittleWorldSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

internal val LittleWorldSkin.paperColor: Color get() = when (this) {
    LittleWorldSkin.MOONLIGHT -> Color(0xFFF4F2FB)
    LittleWorldSkin.SCRAPBOOK -> Color(0xFFFCF8F0)
    LittleWorldSkin.STRAWBERRY -> Color(0xFFFFF6F3)
    LittleWorldSkin.WOODLAND -> Color(0xFFF5F8F0)
}

internal val LittleWorldSkin.noteColor: Color get() = when (this) {
    LittleWorldSkin.MOONLIGHT -> Color(0xFFF3ECFE)
    LittleWorldSkin.SCRAPBOOK -> Color(0xFFFFF4DE)
    LittleWorldSkin.STRAWBERRY -> Color(0xFFFFEDEA)
    LittleWorldSkin.WOODLAND -> Color(0xFFF0F4E5)
}

internal enum class SkinStickerPart { TAB, KEEPSAKE }

// Measured cutouts in the 1024×1536 source: a few generated stickers cross the nominal
// grid boundary. Transparent padding keeps neighbouring pieces out and their edges whole.
private val stickerCutouts = listOf(
    IntRect(120, 7, 436, 395), IntRect(594, 32, 932, 413),
    IntRect(58, 411, 475, 767), IntRect(594, 413, 932, 753),
    IntRect(44, 763, 481, 1115), IntRect(536, 790, 989, 1116),
    IntRect(46, 1144, 506, 1466), IntRect(565, 1133, 970, 1490)
)

/** A single sampled atlas, shared by the skin picker and the scrolling paper decorations. */
@Composable
internal fun LittleWorldSkinSticker(skin: LittleWorldSkin, part: SkinStickerPart, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val art by produceState<ImageBitmap?>(null, resources) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, R.drawable.world_skin_sticker_atlas_v1) }
    }
    val row = when (skin) {
        LittleWorldSkin.MOONLIGHT -> 0
        LittleWorldSkin.SCRAPBOOK -> 1
        LittleWorldSkin.STRAWBERRY -> 2
        LittleWorldSkin.WOODLAND -> 3
    }
    val column = if (part == SkinStickerPart.TAB) 0 else 1
    val source = remember(art, row, column) {
        art?.let { image ->
            val rect = stickerCutouts[row * 2 + column]
            IntRect((rect.left * image.width / 1024f).roundToInt(), (rect.top * image.height / 1536f).roundToInt(),
                (rect.right * image.width / 1024f).roundToInt(), (rect.bottom * image.height / 1536f).roundToInt())
        }
    }
    Canvas(modifier) {
        art?.let { image ->
            source?.let { rect ->
                val factor = min(size.width / rect.width, size.height / rect.height)
                val target = IntSize((rect.width * factor).roundToInt().coerceAtLeast(1), (rect.height * factor).roundToInt().coerceAtLeast(1))
                drawImage(image, srcOffset = IntOffset(rect.left, rect.top), srcSize = IntSize(rect.width, rect.height),
                    dstOffset = IntOffset(((size.width - target.width) / 2).roundToInt(), ((size.height - target.height) / 2).roundToInt()),
                    dstSize = target)
            }
        }
    }
}

/** Picker previews show attached stickers on notes, rather than promising a separate screen frame. */
@Composable
internal fun LittleWorldSkinArtwork(skin: LittleWorldSkin, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth(.88f).height(66.dp).align(Alignment.Center).offset(y = (-8).dp).rotate(-2f)) {
            StickerPaperArtwork(Modifier.matchParentSize(), skin.noteColor)
            Column(Modifier.padding(start = 15.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(42.dp).height(3.dp).background(Color(0xFFB3A3C8)))
                Box(Modifier.width(58.dp).height(2.dp).background(Color(0xFFD2C6DB)))
            }
            LittleWorldSkinSticker(skin, SkinStickerPart.TAB,
                Modifier.size(52.dp, 39.dp).align(Alignment.TopEnd).offset(x = 2.dp, y = (-12).dp).rotate(5f))
        }
        Box(Modifier.width(80.dp).height(35.dp).align(Alignment.BottomEnd).offset(x = (-5).dp, y = (-5).dp).rotate(3f)) {
            StickerPaperArtwork(Modifier.matchParentSize(), skin.paperColor)
            LittleWorldSkinSticker(skin, SkinStickerPart.KEEPSAKE,
                Modifier.size(44.dp, 33.dp).align(Alignment.CenterStart).offset(x = (-10).dp))
        }
    }
}
