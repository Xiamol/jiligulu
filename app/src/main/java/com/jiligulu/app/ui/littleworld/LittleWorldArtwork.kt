package com.jiligulu.app.ui.littleworld

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.content.ComponentCallbacks2
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import com.jiligulu.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared sampled artwork: scrolling many stickers/jars never decodes another full-size copy. */
internal object LittleWorldArtwork {
    private const val MAX_BYTES = 12L * 1024 * 1024
    private val cache = ArtworkMemoryCache<Int, ImageBitmap>(MAX_BYTES) { it.asAndroidBitmap().allocationByteCount.toLong() }
    fun cachedImage(id:Int):ImageBitmap? = cache.get(id)
    fun preload(resources: Resources) {
        // The three main pages share these small assets. Destination paintings already
        // decode on IO when opened; loading them here delays startup for unseen rooms.
        listOf(R.drawable.sticker_paper_painting,R.drawable.sticker_wall_board,R.drawable.sticker_illustrations_atlas,
            R.drawable.world_skin_sticker_atlas_v1,R.drawable.world_interactive_room_v3).forEach { image(resources,it) }
    }
    fun image(resources: Resources, id: Int): ImageBitmap = cache.getOrLoad(id) {
        BitmapFactory.decodeResource(resources,id,BitmapFactory.Options().apply {
            inSampleSize=if(id==R.drawable.world_scene_atlas_anime || id==R.drawable.world_interactive_room_v3 ||
                id==R.drawable.wish_shelf_ocean_palace_v4 || id==R.drawable.world_destination_portraits_v3 ||
                id==R.drawable.world_destination_portraits_v4_empty || id==R.drawable.world_secret_room_portrait_v3 ||
                id==R.drawable.world_secret_room_portrait_v4_night) 1 else 2
            inScaled=false
        }).asImageBitmap()
    }
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) cache.clear()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimTo(MAX_BYTES / 2)
    }
    fun clearMemoryCache() = cache.clear()
}

@Composable
internal fun rememberWorldArtwork(id: Int): ImageBitmap? {
    val resources = LocalContext.current.resources
    val loaded by produceState<Pair<Int, ImageBitmap>?>(LittleWorldArtwork.cachedImage(id)?.let { id to it }, resources, id) {
        value = id to withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, id) }
    }
    return loaded?.takeIf { it.first == id }?.second ?: LittleWorldArtwork.cachedImage(id)
}

@Composable
internal fun StickerPaperArtwork(modifier: Modifier, tint: Color = Color.White) {
    val art=rememberWorldArtwork(R.drawable.sticker_paper_painting)
    val colorFilter=remember(tint) { ColorFilter.tint(tint,BlendMode.Modulate) }
    art?.let { Image(it,null,modifier,contentScale=ContentScale.FillBounds,colorFilter=colorFilter) }
}

private val IllustrationIndices = listOf("🍳","🚇","☕","🍚","🧋","🍊","🥬","🧻","🚕","🅿️","🐱","🍜")
    .mapIndexed { index, emoji -> emoji to index }.toMap()

@Composable
internal fun StickerIllustration(emoji: String, modifier: Modifier, fallbackFontSize: TextUnit? = null) {
    val index=IllustrationIndices[emoji] ?: -1
    if(index<0) {
        val style=MaterialTheme.typography.headlineMedium
        Text(emoji,modifier,style=if(fallbackFontSize==null)style else style.copy(fontSize=fallbackFontSize,lineHeight=fallbackFontSize*1.2f))
        return
    }
    val art=rememberWorldArtwork(R.drawable.sticker_illustrations_atlas)
    val cell=remember(art) { art?.let { IntSize(it.width/4,it.height/3) } }
    val source=remember(index,cell) { cell?.let { IntOffset((index%4)*it.width,(index/4)*it.height) } }
    Canvas(modifier) {
        if (art != null && source != null && cell != null) drawImage(art,srcOffset=source,srcSize=cell,
            dstSize=IntSize(size.width.toInt(),size.height.toInt()))
    }
}

@Composable
internal fun StickerWallArtwork(modifier:Modifier) {
    val art=rememberWorldArtwork(R.drawable.sticker_wall_board)
    art?.let { Image(it,null,modifier,contentScale=ContentScale.FillBounds) }
}
