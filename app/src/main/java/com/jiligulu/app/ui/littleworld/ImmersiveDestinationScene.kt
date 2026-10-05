package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.R
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** One portrait panel in the destination atlas, never stretched to the screen. */
enum class ImmersiveDestination(val panel: Int, val description: String) {
    FUTURE_POST(0, "阿噜的未来邮局，信笺、邮筒和旧信匣"),
    MEMORIES(1, "生活纪念册工作台，相册、照片和明信片工具"),
    TIME_TRAIN(2, "时光列车车站，列车、时钟和旧车票")
}

/** All coordinates refer to the uncropped portrait panel, so taps follow the pictured prop. */
data class DestinationObject(
    val label: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val labelX: Float = left + width / 2f,
    val labelY: Float = top + height,
    val tilt: Float = -2f,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** A viewport-covering painting with small labels attached to its physical objects. */
@Composable
fun ImmersiveDestinationScene(
    destination: ImmersiveDestination,
    title: String,
    onBack: () -> Unit,
    objects: List<DestinationObject>,
    modifier: Modifier = Modifier,
    labelBottomClearance: Dp = 0.dp,
    foreground: @Composable BoxScope.() -> Unit = {}
) {
    SceneSystemBars(lightIcons=destination!=ImmersiveDestination.MEMORIES)
    val resources = LocalContext.current.resources
    val resource = R.drawable.world_destination_portraits_v3
    val decoded by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(resource), resources, resource) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, resource) }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(
        Brush.verticalGradient(listOf(Color(0xFFF0DFBF), Color(0xFFCBA879))))) {
        val image = decoded
        // Atlas source ratio is available before decoding, preventing controls jumping on load.
        val panelWidth = image?.let { it.width / 3f - 8f } ?: 564f
        val panelHeight = image?.let { it.height.toFloat() - 8f } ?: 908f
        val viewportRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val panelRatio = panelWidth / panelHeight
        val paintedWidth = if (viewportRatio > panelRatio) maxWidth else maxHeight * panelRatio
        val paintedHeight = if (viewportRatio > panelRatio) maxWidth / panelRatio else maxHeight
        val left = (maxWidth - paintedWidth) / 2f
        val top = (maxHeight - paintedHeight) / 2f
        Canvas(Modifier.matchParentSize().semantics { contentDescription = destination.description }) {
            image?.let { atlas ->
                val sourceWidth = atlas.width / 3
                val sourceLeft = destination.panel * sourceWidth
                val sourceRight = if (destination.panel == 2) atlas.width else sourceLeft + sourceWidth
                // Insets stop neighboring atlas pixels bleeding into the portrait at fractional scales.
                val cropWidth = sourceRight - sourceLeft - 8
                val cropHeight = atlas.height - 8
                val scale = max(size.width / cropWidth, size.height / cropHeight)
                val drawn = IntSize((cropWidth * scale).roundToInt(), (cropHeight * scale).roundToInt())
                drawImage(atlas, srcOffset = IntOffset(sourceLeft + 4, 4),
                    srcSize = IntSize(cropWidth, cropHeight),
                    dstOffset = IntOffset(((size.width - drawn.width) / 2f).roundToInt(),
                        ((size.height - drawn.height) / 2f).roundToInt()), dstSize = drawn,colorFilter=MutedSceneColorFilter)
            }
        }
        SkinSceneDecor(Modifier.matchParentSize(), paintBackground = false)
        objects.forEach { prop ->
            val rawX = left + paintedWidth * prop.left
            val rawY = top + paintedHeight * prop.top
            val x = rawX.coerceAtLeast(0.dp)
            val y = rawY.coerceAtLeast(0.dp)
            val right = (rawX + paintedWidth * prop.width).coerceAtMost(maxWidth)
            val bottom = (rawY + paintedHeight * prop.height).coerceAtMost(maxHeight)
            if (right > x && bottom > y) {
                Box(Modifier.offset(x, y).size(right - x, bottom - y)
                    .clickable(enabled = prop.enabled, role = Role.Button, onClickLabel = prop.label, onClick = prop.onClick)
                    .semantics { contentDescription = prop.label })
            }
        }
        objects.forEach { prop ->
            val labelWidth = (maxWidth - 20.dp).coerceIn(48.dp, 118.dp)
            val labelX = (left + paintedWidth * prop.labelX - labelWidth / 2f)
                .coerceIn(10.dp, (maxWidth - labelWidth - 10.dp).coerceAtLeast(10.dp))
            val labelY = (top + paintedHeight * prop.labelY)
                .coerceIn(58.dp, (maxHeight - 54.dp - labelBottomClearance).coerceAtLeast(58.dp))
            ScenePlaqueButton(prop.label, Modifier.offset(labelX, labelY).width(labelWidth).heightIn(min = 32.dp).rotate(prop.tilt),
                enabled = prop.enabled, onClick = prop.onClick)
        }
        val titleWidth = (paintedWidth * if(destination == ImmersiveDestination.TIME_TRAIN) .55f else .45f)
            .coerceAtMost((maxWidth - 80.dp).coerceAtLeast(100.dp))
        val titleX = left + paintedWidth * .51f - titleWidth / 2f
        val titleY = top + paintedHeight * (if(destination == ImmersiveDestination.TIME_TRAIN) .087f else .047f)
        Text(title, Modifier.offset(titleX, titleY).width(titleWidth), color = Color(0xFF5E4229),
            fontFamily = GuluBrandFont, fontSize = 17.sp, maxLines = 1, textAlign = TextAlign.Center)
        ScenePlaqueButton("‹ 小窝", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 12.dp)
            .heightIn(min = 44.dp).rotate(-2f), onClick = onBack)
        foreground()
    }
}

/** A small paper ticket, used for information the scene's painted props cannot contain. */
@Composable
fun DestinationPaper(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val u = 1.dp.toPx()
            drawRoundRect(Color(0xFF3D2715).copy(alpha = .18f), Offset(u, 3 * u),
                cornerRadius = CornerRadius(4 * u))
            drawRoundRect(Color(0xFFF3E5C7), cornerRadius = CornerRadius(3 * u))
            drawLine(Color(0xFFB49368).copy(alpha = .45f), Offset(9 * u, 5 * u),
                Offset(size.width - 9 * u, 5 * u), u)
        }
        content()
    }
}

/** Object contents remain bounded and scrollable while the room stays visible behind the drawer. */
@Composable
fun DestinationDrawer(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val height = (LocalConfiguration.current.screenHeightDp * .64f).dp
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        LedgerCard(Modifier.widthIn(max = 410.dp).fillMaxWidth(.9f).height(height)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = GuluBrandFont,
                fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.primary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            SpringLazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                contentPadding = PaddingValues(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                actions()
                TextButton(onClick = onDismiss) { Text("收起来") }
            }
        }
    }
}
