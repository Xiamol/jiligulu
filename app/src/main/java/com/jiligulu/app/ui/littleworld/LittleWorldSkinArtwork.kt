package com.jiligulu.app.ui.littleworld

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.R
import com.jiligulu.app.data.prefs.LittleWorldSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LittleWorldSkin.paperColor: Color get() = when (this) {
    LittleWorldSkin.MOONLIGHT -> Color(0xFFF4F2FB)
    LittleWorldSkin.SCRAPBOOK -> Color(0xFFFCF8F0)
    LittleWorldSkin.STRAWBERRY -> Color(0xFFFFF6F3)
    LittleWorldSkin.WOODLAND -> Color(0xFFF5F8F0)
}

private val LittleWorldSkin.artResource: Int get() = when (this) {
    LittleWorldSkin.MOONLIGHT -> R.drawable.world_skin_moonlight
    LittleWorldSkin.SCRAPBOOK -> R.drawable.world_skin_scrapbook
    LittleWorldSkin.STRAWBERRY -> R.drawable.world_skin_strawberry
    LittleWorldSkin.WOODLAND -> R.drawable.world_skin_woodland
}

private data class SkinArtworkKey(val skin: LittleWorldSkin, val preview: Boolean)

/** Full-resolution page artwork plus small picker thumbnails, bounded independently of other art. */
private object SkinArtworkCache {
    private val cache = object : LruCache<SkinArtworkKey, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: SkinArtworkKey, value: ImageBitmap) = value.width * value.height * 4
    }

    @Synchronized
    fun image(resources: Resources, skin: LittleWorldSkin, preview: Boolean): ImageBitmap {
        val key = SkinArtworkKey(skin, preview)
        return cache.get(key) ?: BitmapFactory.decodeResource(resources, skin.artResource,
            BitmapFactory.Options().apply { inSampleSize = if (preview) 4 else 1; inScaled = false })
            .asImageBitmap().also { cache.put(key, it) }
    }
}

/** Transparent PNGs stay on a non-interactive background layer. Decoding never blocks a drag. */
@Composable
internal fun LittleWorldSkinArtwork(skin: LittleWorldSkin, modifier: Modifier = Modifier, preview: Boolean = false) {
    val resources = LocalContext.current.resources
    key(skin, resources, preview) {
        val art by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) { SkinArtworkCache.image(resources, skin, preview) }
        }
        Box(modifier.background(skin.paperColor)) {
            art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
    }
}
