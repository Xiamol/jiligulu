package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

internal enum class GlobalGlassPhase { OFF, STARTING, ACTIVE, PAUSED }
internal data class GlobalGlassState(val authorizedThisSession: Boolean = false,
    val phase: GlobalGlassPhase = GlobalGlassPhase.OFF, val detail: String = "全局光学未启动")

/** Main-thread, memory-only ROI handoff. This object never requests permission or starts capture. */
internal object GlobalGlassBackdrop {
    private val mutableState = MutableStateFlow(GlobalGlassState())
    val state = mutableState.asStateFlow()
    private var watched = WeakReference<GlassFloatingBubbleView>(null)
    private val location = IntArray(2)
    private var target: GlassRect? = null
    private val motionHistory = GlassMotionHistory()
    @Volatile private var samplingHistory: GlassMotionSnapshot? = null
    private var frameAt = 0L
    private var frameRegion: GlassSampleRegion? = null
    private var frame: Bitmap? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var nextBuffer = 0
    private var targetFps = com.jiligulu.app.data.prefs.GlobalGlassFrameRate.DEFAULT.fps
    private var lens: GlobalEdgeLens? = null
    var rendererUnavailable = false
        private set

    fun watch(view: GlassFloatingBubbleView) {
        watched = WeakReference(view)
        refreshTarget()
        ScreenCaptureService.refreshGlassEnvironment()
    }
    fun unwatch(view: GlassFloatingBubbleView) {
        if (watched.get() === view) {
            clearFrame(); watched.clear(); target = null; motionHistory.clear(); samplingHistory = null
            ScreenCaptureService.refreshGlassEnvironment()
        }
    }
    fun interaction(pressed: Boolean) {
        // Pressing no longer pauses capture. Coordinate history masks the moving icon.
        refreshTarget()
        ScreenCaptureService.refreshGlassEnvironment()
    }
    fun refreshTarget() {
        val view = watched.get()
        val next = if (view != null && view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0) {
            view.getLocationOnScreen(location)
            GlassRect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        } else null
        if (next != target) {
            target = next
            if (next == null) { motionHistory.clear(); samplingHistory = null; clearFrame() }
            else samplingHistory = motionHistory.record(next, System.nanoTime())
            // Existing trustworthy texture follows the latest geometry while the worker
            // obtains another frame. It must not be rebound at its old screen coordinate.
            if (!AppGlassBackdrop.available()) watched.get()?.refreshBackdrop()
            ScreenCaptureService.refreshGlassEnvironment()
        }
    }
    fun currentTarget(): GlassRect? {
        refreshTarget()
        return target
    }
    /** Worker-safe: immutable coordinates only, never an Android View or another window. */
    fun samplingTarget(frameNanos: Long): GlassSamplingTarget? = samplingHistory?.forFrame(frameNanos)
    fun setTargetFps(fps: Int) {
        targetFps = fps
        if(mutableState.value.phase==GlobalGlassPhase.ACTIVE) mutableState.value=mutableState.value.copy(
            detail="全图光学近似 · 目标 $targetFps 帧/秒 · 仅本机内存")
    }
    fun session(enabled: Boolean, phase: GlobalGlassPhase, detail: String) {
        if (!enabled) rendererUnavailable = false
        mutableState.value = GlobalGlassState(enabled, phase, detail)
        if (phase != GlobalGlassPhase.ACTIVE) clearFrame()
    }
    fun available(): Boolean = mutableState.value.authorizedThisSession && frame != null &&
        !AppGlassBackdrop.available() && mutableState.value.phase == GlobalGlassPhase.ACTIVE &&
        watched.get()?.isShown == true

    fun publish(region: GlassSampleRegion, pixels: IntArray, usable: Boolean) {
        if (!usable || !mutableState.value.authorizedThisSession || AppGlassBackdrop.available() || currentTarget() == null) {
            clearFrame()
            return
        }
        val index = nextBuffer
        nextBuffer = 1 - nextBuffer
        val width = region.roi.width
        val height = region.roi.height
        val bitmap = buffers[index]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[index]?.eraseColor(0); buffers[index] = it }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        frame = bitmap; frameRegion = region; frameAt = SystemClock.uptimeMillis()
        mutableState.value = GlobalGlassState(true, GlobalGlassPhase.ACTIVE, "全图光学近似 · 目标 $targetFps 帧/秒 · 仅本机内存")
        watched.get()?.refreshBackdrop()
    }
    fun keepFresh(region: GlassSampleRegion):Boolean {
        if (frameRegion == region && frame != null && mutableState.value.authorizedThisSession &&
            !AppGlassBackdrop.available() && currentTarget() != null) {
            frameAt = SystemClock.uptimeMillis();return true
        }
        return false
    }

    @RequiresApi(33)
    fun shaderFor(view: GlassFloatingBubbleView, lightX: Float, lightY: Float): Shader? {
        if (!available() || watched.get() !== view) return null
        val current = currentTarget() ?: return null
        val sampled = frameRegion ?: return null
        val safeNow = GlassRect(kotlin.math.floor(current.left * sampled.scaleX).toInt() - 3,
            kotlin.math.floor(current.top * sampled.scaleY).toInt() - 3,
            kotlin.math.ceil(current.right * sampled.scaleX).toInt() + 3,
            kotlin.math.ceil(current.bottom * sampled.scaleY).toInt() + 3)
        val region = sampled.copy(bubble = current, excluded = sampled.excluded.union(safeNow))
        val bitmap = frame ?: return null
        return try {
            (lens ?: GlobalEdgeLens().also { lens = it }).bind(bitmap, region, view.width, view.height, lightX, lightY)
        } catch (failure: Exception) {
            rendererUnavailable = true
            mutableState.value = GlobalGlassState(true, GlobalGlassPhase.PAUSED, "设备当前无法使用光学，保留基础透明玻璃")
            clearFrame()
            ScreenCaptureService.refreshGlassEnvironment()
            android.util.Log.w("GlobalGlass", "Edge shader unavailable; sampling will stop", failure)
            null
        }
    }

    fun clearFrame() {
        frame = null; frameRegion = null; frameAt = 0L
        watched.get()?.clearGlobalBackdrop()
        buffers.forEach { it?.eraseColor(0) }
    }
    fun release() { clearFrame(); buffers.fill(null); nextBuffer = 0; lens = null }
}
