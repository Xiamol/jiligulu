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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.animation.core.spring
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
    val breathing = if (onClick != null || mode != MascotMode.IDLE) rememberInfiniteTransition(label = "mochiBreathing").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "softBreath"
    ) else null
    val bounce = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val resourceId = when (mode) {
        MascotMode.IDLE -> R.drawable.gulu_idle
        MascotMode.WAITING -> R.drawable.gulu_waiting
        MascotMode.DRINKING -> R.drawable.gulu_drinking
    }
    val artwork = remember(resourceId) { MascotArtwork.load(resources, resourceId) }
    val click = if (onClick == null) Modifier else Modifier.pointerInput(onClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = { scope.launch { bounce.animateTo(.82f, tween(120)); tilt.animateTo(-5f, tween(100)) } },
            onPress = {
                scope.launch { bounce.animateTo(.94f, tween(80)) }
                tryAwaitRelease()
                scope.launch {
                    bounce.animateTo(1f, spring(dampingRatio = .6f, stiffness = 500f))
                    tilt.animateTo(0f, tween(140))
                }
            }
        )
    }.semantics {
        onClick(label = "和阿噜说句话") { onClick(); true }
    }
    Image(
        bitmap = artwork,
        contentDescription = when {
            mode == MascotMode.WAITING -> "咕噜拿着水杯等你，点击一起喝水"
            mode == MascotMode.DRINKING -> "咕噜正在喝水"
            onClick != null -> "叽里咕噜，点一下换句话，长按捏捏阿噜"
            else -> null
        },
        contentScale = ContentScale.Fit,
        modifier = modifier.then(click).graphicsLayer {
            scaleX = bounce.value
            scaleY = (breathing?.value ?: 1f) * bounce.value
            rotationZ = tilt.value
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
