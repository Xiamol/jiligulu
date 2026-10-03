package com.jiligulu.app.ui.littleworld

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jiligulu.app.R
import java.util.concurrent.ConcurrentHashMap

/** Shared sampled artwork: scrolling many stickers/jars never decodes another full-size copy. */
internal object LittleWorldArtwork {
    private val cache = ConcurrentHashMap<Int, ImageBitmap>()
    fun preload(resources: Resources) {
        listOf(R.drawable.sticker_paper_painting,R.drawable.sticker_wall_board,R.drawable.sticker_illustrations_atlas,R.drawable.wish_star_bottle).forEach { image(resources,it) }
    }
    fun image(resources: Resources, id: Int): ImageBitmap = cache.computeIfAbsent(id) {
        BitmapFactory.decodeResource(resources,id,BitmapFactory.Options().apply { inSampleSize=2; inScaled=false }).asImageBitmap()
    }
}

@Composable
internal fun StickerPaperArtwork(modifier: Modifier, tint: Color = Color.White) {
    val resources=LocalContext.current.resources
    val art=remember { LittleWorldArtwork.image(resources,R.drawable.sticker_paper_painting) }
    Image(art,null,modifier,contentScale=ContentScale.FillBounds,colorFilter=ColorFilter.tint(tint,BlendMode.Modulate))
}

@Composable
internal fun StickerIllustration(emoji: String, modifier: Modifier) {
    val index=listOf("🍳","🚇","☕","🍚","🧋","🍊","🥬","🧻","🚕","🅿️","🐱","🍜").indexOf(emoji)
    if(index<0) { Text(emoji,modifier,style=MaterialTheme.typography.headlineMedium);return }
    val resources=LocalContext.current.resources
    val art=remember { LittleWorldArtwork.image(resources,R.drawable.sticker_illustrations_atlas) }
    Canvas(modifier) {
        val cell=IntSize(art.width/4,art.height/3)
        drawImage(art,srcOffset=IntOffset((index%4)*cell.width,(index/4)*cell.height),srcSize=cell,
            dstSize=IntSize(size.width.toInt(),size.height.toInt()))
    }
}

@Composable
internal fun StickerWallArtwork(modifier:Modifier) {
    val resources=LocalContext.current.resources
    val art=remember { LittleWorldArtwork.image(resources,R.drawable.sticker_wall_board) }
    Image(art,null,modifier,contentScale=ContentScale.FillBounds)
}
