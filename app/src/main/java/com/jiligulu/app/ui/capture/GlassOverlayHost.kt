package com.jiligulu.app.ui.capture

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.jiligulu.app.R
import java.util.function.Consumer
import kotlin.math.min
import kotlin.math.roundToInt

/** All three renderers use the same full-window bounds; only the visible rim is inset. */
internal object GlassBubbleGeometry {
    fun cornerRadius(width: Int, height: Int): Float = min(width, height) * .24f
}

/**
 * Android's BackgroundBlurDrawable produces a localized SurfaceFlinger blurRegions entry.
 * backgroundBlurRadius in the layer dump may remain zero: that is a different, whole-layer effect.
 * No background pixels from other applications are read by this host.
 */
internal class GlassOverlayHost(private val context: Context, private val manager: WindowManager) {
    private var dialog: Dialog? = null
    private var standalone: View? = null
    private var materialWidth = 0
    private var materialHeight = 0
    private var materialBlurEnabled: Boolean? = null
    private var blurListener: Consumer<Boolean>? = null
    private var lastDiagnostic: String? = null

    private fun layout(source: WindowManager.LayoutParams, platform: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            copyFrom(source)
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = (flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
                (WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND).inv()
            dimAmount = 0f
            title = if (platform) "JiliguluGlass/Platform" else "JiliguluGlass/Fallback"
        }

    private fun background(window: Window, enabled: Boolean, applyBlur: Boolean = true) {
        val width = window.attributes.width.coerceAtLeast(1)
        val height = window.attributes.height.coerceAtLeast(1)
        if (materialWidth != width || materialHeight != height || materialBlurEnabled != enabled) {
            // AOSP DecorView uses the background's corner radius, not its inset outline rectangle.
            // An InsetDrawable also adds decor padding, leaving blur outside the drawn glass shell.
            window.setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.argb(if (enabled) 8 else 34, 248, 245, 255))
                cornerRadius = GlassBubbleGeometry.cornerRadius(width, height)
            })
            window.decorView.setPadding(0, 0, 0, 0)
            materialWidth = width
            materialHeight = height
            materialBlurEnabled = enabled
        }
        if (Build.VERSION.SDK_INT >= 31 && applyBlur && window.decorView.isAttachedToWindow) {
            window.setBackgroundBlurRadius(if (enabled) (min(width, height) * .30f).roundToInt() else 0)
        }
    }

    fun attach(view: View, params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 31) {
            var candidate: Dialog? = null
            var phase = "create-dialog"
            try {
                val created = Dialog(context, R.style.Theme_Jiligulu_GlassOverlay)
                candidate = created
                created.setCancelable(false)
                created.setCanceledOnTouchOutside(false)
                val window = checkNotNull(created.window)
                // Set the overlay type before decor inflation so its initial policy sees the right type.
                window.attributes = layout(params, platform = true)
                phase = "install-content"
                created.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // Floating decor inflation applies WRAP_CONTENT defaults; restore the service's
                // exact size/position after inflation, without reintroducing dim or whole-screen blur.
                window.attributes = layout(params, platform = true)
                window.decorView.elevation = 0f
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                background(window, manager.isCrossWindowBlurEnabled, applyBlur = false)
                created.setOnShowListener {
                    // DecorView now has a ViewRootImpl; no retry loop or periodic invalidation is needed.
                    window.decorView.post {
                        if (dialog === created && window.decorView.isAttachedToWindow) {
                            runCatching { background(window, manager.isCrossWindowBlurEnabled) }
                                .onFailure { failure("apply-attached-blur", it) }
                            registerBlurListener(created, view)
                            window.decorView.postOnAnimation {
                                if (dialog === created) diagnose(window, "attached")
                            }
                        }
                    }
                }
                phase = "show-dialog"
                dialog = created
                created.show()
                return
            } catch (error: Exception) {
                failure(phase, error)
                unregisterBlurListener()
                dialog = null
                runCatching { candidate?.dismiss() }.onFailure { failure("dismiss-failed-dialog", it) }
                (view.parent as? ViewGroup)?.removeView(view)
                materialWidth = 0
                materialHeight = 0
                materialBlurEnabled = null
            }
        }
        try {
            manager.addView(view, layout(params, platform = false))
            standalone = view
            Log.i(TAG, "mode=fallback sdk=${Build.VERSION.SDK_INT}; reason=${if (Build.VERSION.SDK_INT < 31) "api-below-31" else "dialog-attach-failed"}")
        } catch (error: Exception) {
            failure("attach-fallback", error)
            throw error
        }
    }

    private fun registerBlurListener(owner: Dialog, view: View) {
        if (Build.VERSION.SDK_INT < 31 || blurListener != null) return
        val listener = Consumer<Boolean> { enabled ->
            if (dialog === owner) owner.window?.let { window ->
                runCatching { background(window, enabled); view.invalidate(); diagnose(window, "blur-state") }
                    .onFailure { failure("update-blur-state", it) }
            }
        }
        runCatching { manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener) }
            .onSuccess { blurListener = listener }
            .onFailure { failure("register-blur-listener", it) }
    }

    fun update(params: WindowManager.LayoutParams) {
        try {
            val window = dialog?.window
            if (window != null) {
                window.attributes = layout(params, platform = true)
                background(window, Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled)
            } else standalone?.let { manager.updateViewLayout(it, layout(params, platform = false)) }
        } catch (error: Exception) {
            failure("update-layout", error)
            throw error
        }
    }

    fun visible(show: Boolean) {
        dialog?.let { owner ->
            if (show) {
                owner.show()
                owner.window?.let { window ->
                    window.decorView.post {
                        if (dialog === owner && window.decorView.isAttachedToWindow) {
                            runCatching { background(window, Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled) }
                                .onFailure { failure("restore-visible-blur", it) }
                        }
                    }
                }
            } else owner.hide()
        }
        standalone?.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    fun detach() {
        unregisterBlurListener()
        val previousDialog = dialog
        dialog = null
        previousDialog?.dismiss()
        standalone?.let { manager.removeView(it) }
        standalone = null
        materialWidth = 0
        materialHeight = 0
        materialBlurEnabled = null
        lastDiagnostic = null
    }

    private fun unregisterBlurListener() {
        if (Build.VERSION.SDK_INT >= 31) blurListener?.let { listener ->
            runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
                .onFailure { failure("remove-blur-listener", it) }
        }
        blurListener = null
    }

    /** Geometry and API state only: no titles from other apps, pixels, tokens, or user content. */
    private fun diagnose(window: Window, phase: String) {
        val decor = window.decorView
        val attrs = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent))
        val theme = try { "floating=${attrs.getBoolean(0, false)} translucent=${attrs.getBoolean(1, false)}" } finally { attrs.recycle() }
        val enabled = Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled
        val state = "mode=platform sdk=${Build.VERSION.SDK_INT} $theme enabled=$enabled " +
            "attached=${decor.isAttachedToWindow} hw=${decor.isHardwareAccelerated} " +
            "size=${decor.width}x${decor.height} content=${materialWidth}x${materialHeight} " +
            "requestedRadius=${if (enabled) (min(materialWidth, materialHeight) * .30f).roundToInt() else 0} " +
            "corner=${GlassBubbleGeometry.cornerRadius(materialWidth, materialHeight)} " +
            "background=${drawableKinds(decor.background)}"
        if (state != lastDiagnostic) { lastDiagnostic = state; Log.i(TAG, "$phase $state") }
    }

    private fun drawableKinds(drawable: Drawable?): String = when (drawable) {
        null -> "none"
        is LayerDrawable -> "LayerDrawable[" + (0 until drawable.numberOfLayers).joinToString { drawableKinds(drawable.getDrawable(it)) } + "]"
        is InsetDrawable -> "InsetDrawable[${drawableKinds(drawable.drawable)}]"
        else -> drawable.javaClass.simpleName
    }

    private fun failure(phase: String, error: Throwable) {
        val site = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
        Log.w(TAG, "phase=$phase failed=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName ?: "none"} at=$site")
    }

    private companion object { const val TAG = "GlassOverlay" }
}
