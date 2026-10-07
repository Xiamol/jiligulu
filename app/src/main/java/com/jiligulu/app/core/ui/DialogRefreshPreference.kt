package com.jiligulu.app.core.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Compose dialogs have separate windows; keep their cadence with the containing App. */
@Composable
internal fun DialogRefreshPreference(window: Window?) {
    val view = LocalView.current
    val parentWindow = remember(view) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window
    }
    if (window == null || parentWindow == null || window === parentWindow) return
    val hints = remember(parentWindow) { AppWindowRefreshHints.forWindow(parentWindow) }
    val hint by hints.collectAsStateWithLifecycle()
    DisposableEffect(window, hint) {
        val attributes = window.attributes
        val before = attributes.preferredDisplayModeId to attributes.preferredRefreshRate
        val owned = hint?.let { it.modeId to it.refreshRate }
        val applied = owned != null && owned != before && runCatching {
            attributes.preferredDisplayModeId = owned.first
            attributes.preferredRefreshRate = owned.second
            window.attributes = attributes
        }.isSuccess
        onDispose {
            if (applied && owned != null) {
                val current = window.attributes
                if ((current.preferredDisplayModeId to current.preferredRefreshRate) == owned) runCatching {
                    current.preferredDisplayModeId = before.first
                    current.preferredRefreshRate = before.second
                    window.attributes = current
                }
            }
        }
    }
}
