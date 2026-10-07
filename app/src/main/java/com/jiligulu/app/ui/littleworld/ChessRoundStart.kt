package com.jiligulu.app.ui.littleworld

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.delay

/** A fresh empty round announces its first player once; undo and restored positions do not replay it. */
@Composable
internal fun ChessRoundStart(identity: Any, ready: Boolean, emptyBoard: Boolean, text: String) {
    var announced by remember(identity) { mutableStateOf(false) }
    var visible by remember(identity) { mutableStateOf(false) }
    val alpha = remember(identity) { Animatable(0f) }
    LaunchedEffect(identity, ready) {
        if (ready && emptyBoard && !announced) {
            announced = true
            visible = true
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(150))
            delay(800)
            alpha.animateTo(0f, tween(350))
            visible = false
        }
    }
    if (visible) Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false)) {
        Box(Modifier.graphicsLayer { this.alpha = alpha.value }.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF5C4E61), fontFamily = GuluBrandFont, fontSize = 36.sp,
                letterSpacing = 4.sp)
        }
    }
}
