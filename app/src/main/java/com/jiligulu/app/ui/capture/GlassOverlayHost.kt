package com.jiligulu.app.ui.capture

import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import kotlin.math.min

internal object GlassBubbleGeometry {
    fun cornerRadius(width: Int, height: Int): Float = min(width, height) * .24f
}

/** One transparent surface; no blur, platform dialog decor or competing material. */
internal class GlassOverlayHost(private val manager: WindowManager) {
    private var content: View? = null
    private fun layout(source: WindowManager.LayoutParams) = WindowManager.LayoutParams().apply {
        copyFrom(source)
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = (flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
            (WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND).inv()
        dimAmount = 0f
        title = "JiliguluGlass"
    }
    fun attach(view: View, params: WindowManager.LayoutParams) {
        manager.addView(view, layout(params)); content = view
    }
    fun update(params: WindowManager.LayoutParams) { content?.let { manager.updateViewLayout(it, layout(params)) } }
    fun visible(show: Boolean) { content?.visibility = if (show) View.VISIBLE else View.INVISIBLE }
    fun detach() { content?.let(manager::removeView); content = null }
}
