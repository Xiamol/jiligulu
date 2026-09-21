package com.jiligulu.app.ui.persona

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.jiligulu.app.ui.theme.GuluTheme

/** A soft, slightly asymmetrical speech cloud with a curved tail toward the pet. */
private val CompanionBubbleShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.20f, 0f)
    cubicTo(w * 0.46f, 0f, w * 0.83f, 0f, w * 0.93f, h * 0.09f)
    cubicTo(w, h * 0.17f, w, h * 0.59f, w * 0.98f, h * 0.80f)
    quadraticTo(w * 0.96f, h, w * 0.80f, h)
    lineTo(w * 0.22f, h)
    quadraticTo(w * 0.07f, h, w * 0.065f, h * 0.79f)
    quadraticTo(w * 0.04f, h * 0.78f, 0f, h * 0.71f)
    quadraticTo(w * 0.045f, h * 0.67f, w * 0.05f, h * 0.55f)
    lineTo(w * 0.055f, h * 0.26f)
    quadraticTo(w * 0.065f, 0f, w * 0.20f, 0f)
    close()
}

/** A reserved part of the header: the pet and its words never cover page content. */
@Composable
fun GuluCompanionHeader(
    message: BubbleMessage,
    onRefresh: () -> Unit,
    onWaterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(100.dp, 108.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            val shadow = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            Canvas(
                Modifier.padding(bottom = 4.dp).size(60.dp, 9.dp)
            ) { drawOval(shadow) }
            GuluMascot(
                modifier = Modifier.size(106.dp),
                mode = if (message.kind == BubbleMessage.Kind.WATER) MascotMode.WAITING else MascotMode.IDLE,
                onClick = if (message.kind == BubbleMessage.Kind.WATER) onWaterClick else onRefresh
            )
        }
        Column(
            modifier = Modifier.weight(1f)
                .clip(CompanionBubbleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CompanionBubbleShape)
                .clickable(
                    onClickLabel = if (message.kind == BubbleMessage.Kind.WATER) "一起喝一口" else "换一句",
                    onClick = if (message.kind == BubbleMessage.Kind.WATER) onWaterClick else onRefresh
                )
                .padding(start = 24.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (message.kind == BubbleMessage.Kind.WATER) "水杯备好啦 · 点点我" else "咕噜在这里",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (message.kind == BubbleMessage.Kind.WATER) Icons.Outlined.LocalDrink else Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(5.dp))
            Crossfade(targetState = message.text, animationSpec = tween(220), label = "companionLine") { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                )
            }
        }
    }
}

@Preview(name = "常驻桌宠 · 浅色", widthDp = 360, showBackground = true)
@Composable
private fun CompanionLightPreview() {
    GuluTheme(darkTheme = false) {
        GuluCompanionHeader(BubbleMessage(0, "生活慢慢来，账本我帮你收好，阿噜！", BubbleMessage.Kind.IDLE),
            {}, {}, Modifier.padding(20.dp))
    }
}

@Preview(name = "常驻桌宠 · 深色", widthDp = 360, showBackground = true)
@Composable
private fun CompanionDarkPreview() {
    GuluTheme(darkTheme = true) {
        GuluCompanionHeader(BubbleMessage(0, "忙了一会儿，记得喝口水，阿噜！", BubbleMessage.Kind.WATER),
            {}, {}, Modifier.background(MaterialTheme.colorScheme.background).padding(20.dp))
    }
}
