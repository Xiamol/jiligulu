package com.jiligulu.app.ui.littleworld

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal fun SceneSystemBars(lightIcons:Boolean) {
    val view=LocalView.current
    val activity=remember(view) {
        var context=view.context
        while(context is ContextWrapper && context !is Activity) context=context.baseContext
        context as? Activity
    }
    SideEffect {
        activity?.let {WindowCompat.getInsetsController(it.window,view).isAppearanceLightStatusBars=!lightIcons}
    }
}
