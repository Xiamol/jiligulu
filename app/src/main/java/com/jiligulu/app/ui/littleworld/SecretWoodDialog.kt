package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
        SecretWoodSurface(Modifier.testTag("secret-wood-dialog").widthIn(max = compactWidth).fillMaxWidth()
            .heightIn(max = (configuration.screenHeightDp * .74f).dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 42.dp, bottom = 22.dp)) {
            Text(title, Modifier.fillMaxWidth().padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, fontWeight = FontWeight.Medium, color = SecretWoodInk)
            SpringScrollColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            val closingLabels = setOf("知道啦", "知道了", "收起来", "收起", "好啦", "关闭", "取消")
            val showConfirm = confirmLabel !in closingLabels && (confirmLabel != "收好" || onConfirm !== onDismiss)
            val actionDismiss = dismissLabel?.takeUnless { it in closingLabels || it == "稍后" }
            if (showConfirm || actionDismiss != null) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                actionDismiss?.let { label ->
                    WoodDialogAction(label, Modifier.weight(1f).testTag("secret-wood-dismiss"), enabled = !busy) {
                        UiSound.play(context, closeCue); onDismiss()
                    }
                }
                if (showConfirm) WoodDialogAction(if (busy) "稍等…" else confirmLabel, Modifier.weight(1f).testTag("secret-wood-confirm"),
                    enabled = !busy && confirmEnabled, emphasized = true) {
                    UiSound.play(context, actionCue); onConfirm()
                }
            }
        }
    }
}

@Composable
private fun WoodDialogAction(label: String, modifier: Modifier, enabled: Boolean, emphasized: Boolean = false,
    onClick: () -> Unit) {
    Box(modifier.heightIn(min = 42.dp)
        .sceneClickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(horizontal = 3.dp, vertical = 8.dp),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = SecretWoodInk.copy(alpha = if (!enabled) .4f else if (emphasized) 1f else .72f))
    }
}
