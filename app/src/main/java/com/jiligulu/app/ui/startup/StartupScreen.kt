package com.jiligulu.app.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont

@Composable
fun StartupScreen(error: String?, onRetry: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(480)) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        .testTag("startup-animation"), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).graphicsLayer {
            alpha = 0.3f + entrance.value * 0.7f
            translationY = (1f - entrance.value) * 24f
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GuluMascot(Modifier.size(132.dp).graphicsLayer {
                scaleX = 0.86f + entrance.value * 0.14f
                scaleY = scaleX
            })
            Text("叽里咕噜", fontFamily = GuluBrandFont, fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
            Text(error ?: "把小账本准备好，阿噜 ♡", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
