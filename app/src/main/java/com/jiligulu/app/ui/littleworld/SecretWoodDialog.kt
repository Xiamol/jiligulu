package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.ui.components.SpringScrollColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val SecretWoodInk = Color(0xFF584F49)

/** Nine-sliced artwork: petals, brass nails and folded cloth keep their original proportions. */
@Composable
internal fun SecretWoodSurface(modifier: Modifier = Modifier, framed: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit) {
    val resources = LocalContext.current.resources
    val resource=R.drawable.secret_wood_surface_v2
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(resource),resources,resource) {
        value=withContext(Dispatchers.IO){LittleWorldArtwork.image(resources,resource)}
    }
    Box(modifier.drawWithContent {
        val bitmap=art
        if(bitmap==null) {drawRoundRect(Color(0xFFE9E1D6),cornerRadius=androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()));drawContent();return@drawWithContent}
        if (!framed) {
            drawImage(bitmap, srcOffset = IntOffset(bitmap.width / 4, bitmap.height / 4),
                srcSize = IntSize(bitmap.width / 2, bitmap.height / 2), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium)
        } else {
            val corner = minOf(62.dp.toPx(), size.width / 3f, size.height / 3f)
            val sx = intArrayOf(0,(bitmap.width*340f/1440f).roundToInt(),bitmap.width-(bitmap.width*450f/1440f).roundToInt(),bitmap.width)
            val sy = intArrayOf(0,(bitmap.height*320f/1080f).roundToInt(),bitmap.height-(bitmap.height*320f/1080f).roundToInt(),bitmap.height)
            val dx = floatArrayOf(0f, corner, size.width - corner * 450f / 340f, size.width)
            val dy = floatArrayOf(0f, corner * .94f, size.height - corner * .94f, size.height)
            for (y in 0..2) for (x in 0..2) {
                drawImage(bitmap, srcOffset = IntOffset(sx[x], sy[y]), srcSize = IntSize(sx[x+1]-sx[x], sy[y+1]-sy[y]),
                    dstOffset = IntOffset(dx[x].roundToInt(), dy[y].roundToInt()),
                    dstSize = IntSize((dx[x+1]-dx[x]).roundToInt().coerceAtLeast(1), (dy[y+1]-dy[y]).roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium)
            }
        }
        drawContent()
    }) {
        CompositionLocalProvider(LocalContentColor provides SecretWoodInk) {
            Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
        }
    }
}

@Composable
internal fun SecretWoodDialog(title: String, onDismiss: () -> Unit,
    confirmLabel: String = "收好", onConfirm: () -> Unit = onDismiss, dismissLabel: String? = null,
    busy: Boolean = false, confirmEnabled: Boolean = true, compactWidth: Dp = 292.dp,
    actionCue: UiCue = UiCue.TOUCH, closeCue: UiCue = UiCue.PAPER,
    content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        SecretWoodSurface(Modifier.widthIn(max = compactWidth).fillMaxWidth()
            .heightIn(max = (configuration.screenHeightDp * .74f).dp), contentPadding = PaddingValues(18.dp, 20.dp)) {
            Text(title, Modifier.padding(start = 46.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium, color = SecretWoodInk)
            SpringScrollColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.End) {
                dismissLabel?.let { label -> TextButton(onClick = { UiSound.play(context,closeCue); onDismiss() }, enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) { Text(label) } }
                TextButton(onClick = { UiSound.play(context,actionCue); onConfirm() }, enabled = !busy && confirmEnabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) {
                    Text(if (busy) "等小桌落稳…" else confirmLabel)
                }
            }
        }
    }
}
