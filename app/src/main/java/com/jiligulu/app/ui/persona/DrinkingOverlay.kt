package com.jiligulu.app.ui.persona

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.media.MediaPlayer
import com.jiligulu.app.R
import kotlinx.coroutines.delay

/** Waiting → raise cup → sip → happy. Cancellation keeps the pending cup for next time. */
@Composable
fun DrinkingOverlay(visible: Boolean, onFinished: () -> Unit, onCancel: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    var phase by remember { mutableStateOf(MascotMode.WAITING) }
    val tilt = remember { Animatable(0f) }
    val context = LocalContext.current
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        phase = MascotMode.WAITING
        tilt.snapTo(0f)
        delay(350)
        phase = MascotMode.DRINKING
        // 喝水音效：进入举杯阶段时播放「咕噜咕噜」
        val player = try {
            MediaPlayer.create(context, R.raw.water_glug)?.apply {
                setVolume(1.0f, 1.0f)
                start()
            }
        } catch (_: Exception) { null }
        try {
            repeat(3) {
                tilt.animateTo(-5f, tween(240))
                tilt.animateTo(1f, tween(240))
            }
            tilt.animateTo(0f, tween(160))
        } finally {
            player?.let { p ->
                try { if (p.isPlaying) p.stop(); p.release() } catch (_: Exception) {}
            }
        }
        phase = MascotMode.IDLE
        delay(650)
        finished()
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.54f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .testTag("drinking-animation"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GuluMascot(Modifier.size(220.dp).graphicsLayer { rotationZ = tilt.value }, mode = phase)
                Spacer(Modifier.height(16.dp))
                Text(if (phase == MascotMode.IDLE) "喝好啦，舒服一点了，阿噜 ♡" else "咕噜咕噜……一起喝一口",
                    style = MaterialTheme.typography.titleMedium, color = Color.White)
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 10.dp)) {
                    Text("先等等", color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}
