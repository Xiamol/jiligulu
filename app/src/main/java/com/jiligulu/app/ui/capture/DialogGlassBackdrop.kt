package com.jiligulu.app.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Our own Compose dialogs are eligible; external windows are never registered. */
@Composable
internal fun DialogGlassBackdrop() {
    val window=(LocalView.current.parent as?DialogWindowProvider)?.window
    com.jiligulu.app.core.ui.DialogRefreshPreference(window)
    DisposableEffect(window) {
        if(window!=null) AppGlassBackdrop.dialog(window,true)
        onDispose {if(window!=null) AppGlassBackdrop.dialog(window,false)}
    }
}
