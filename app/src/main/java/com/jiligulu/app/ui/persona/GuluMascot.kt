package com.jiligulu.app.ui.persona

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.R
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class MascotMode { IDLE, WAITING, DRINKING }

/** Approved mochi-cloud artwork; animation transforms never change the surrounding layout. */
@Composable
fun GuluMascot(
    modifier: Modifier = Modifier,
    mode: MascotMode = MascotMode.IDLE,
    onClick: (() -> Unit)? = null
) {
    val breathing = rememberInfiniteTransition(label = "mochiBreathing").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "softBreath"
    )
    val bounce = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val resourceId = when (mode) {
        MascotMode.IDLE -> R.drawable.gulu_idle
        MascotMode.WAITING -> R.drawable.gulu_waiting
        MascotMode.DRINKING -> R.drawable.gulu_drinking
    }
    val artwork = remember(resourceId) { MascotArtwork.load(resources, resourceId) }
    val click = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClickLabel = "和咕噜说句话",
        onClick = {
            onClick()
            scope.launch {
                bounce.snapTo(0.92f)
                bounce.animateTo(1.07f, tween(120))
                bounce.animateTo(1f, tween(180))
            }
        }
    )
    Image(
        bitmap = artwork,
        contentDescription = when {
            mode == MascotMode.WAITING -> "咕噜拿着水杯等你，点击一起喝水"
            mode == MascotMode.DRINKING -> "咕噜正在喝水"
            onClick != null -> "叽里咕噜，点一下换句话"
            else -> null
        },
        contentScale = ContentScale.Fit,
        modifier = modifier.then(click).graphicsLayer {
            scaleX = bounce.value
            scaleY = breathing.value * bounce.value
        }
    )
}

/** Every visible chat avatar reuses one sampled bitmap instead of decoding a full-size image. */
private object MascotArtwork {
    private val images = ConcurrentHashMap<Int, ImageBitmap>()
    fun load(resources: Resources, id: Int): ImageBitmap = images.getOrPut(id) {
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply {
            inSampleSize = 2
            inScaled = false
        }).asImageBitmap()
    }
}
