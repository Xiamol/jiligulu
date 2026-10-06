package com.jiligulu.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.core.audio.UiCue

/** Use for ordinary controls; board selection and placement have their own sound. */
@Composable
fun uiTap(action: () -> Unit): () -> Unit = uiTap(UiCue.TOUCH, action)

@Composable
fun uiTap(cue: UiCue, action: () -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val latest by rememberUpdatedState(action)
    return remember(context, cue) { { UiSound.play(context, cue); latest() } }
}
