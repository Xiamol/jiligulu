package com.jiligulu.app.core.ui

import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi

internal data class DisplayRateMode(val width: Int, val height: Int, val refreshRate: Float)

/** A supported higher cadence is a window preference, never a claimed device FPS. */
internal fun selectSmoothRefreshRate(
    apiLevel: Int,
    powerSave: Boolean,
    preferredModeId: Int,
    preferredRate: Float,
    current: DisplayRateMode,
    supported: List<DisplayRateMode>
): Float? {
    // API 34 documents preferredRefreshRate as a seamless-only Surface frame-rate hint.
    // Older platforms retain their normal policy rather than switching their default mode.
    if (apiLevel < 34 || powerSave || preferredModeId != 0 || preferredRate != 0f ||
        current.width <= 0 || current.height <= 0 || !current.refreshRate.isFinite() || current.refreshRate <= 0f) return null
    val highest = supported.asSequence().filter {
        it.width == current.width && it.height == current.height && it.refreshRate.isFinite() && it.refreshRate > 0f
    }.maxOfOrNull { it.refreshRate } ?: return null
    return highest.takeIf { it > current.refreshRate + .5f }
}

/**
 * Request once per resumed, attached activity window. Respect an existing app/window choice,
 * save mode and system acceptance. Pausing removes only the hint owned by this controller.
 * This never changes preferredDisplayModeId, device settings, or a resolution.
 */
internal class WindowRefreshPreference(private val activity: ComponentActivity) {
    private var resumed = false
    private var ownedRate: Float? = null
    private var pendingAttach: View.OnAttachStateChangeListener? = null

    fun onResume() {
        if (resumed) return
        resumed = true
        if (Build.VERSION.SDK_INT < 34) return
        val decor = activity.window.decorView
        if (decor.isAttachedToWindow) applyIfEligible()
        else {
            pendingAttach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    pendingAttach = null
                    if (resumed && Build.VERSION.SDK_INT >= 34) applyIfEligible()
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            }.also(decor::addOnAttachStateChangeListener)
        }
    }

    @RequiresApi(34)
    private fun applyIfEligible() {
        if (!resumed || ownedRate != null || !activity.window.decorView.isAttachedToWindow) return
        val power = activity.getSystemService(PowerManager::class.java) ?: return
        val display = activity.display ?: activity.window.decorView.display ?: return
        val mode = display.mode
        val attributes = activity.window.attributes
        val rate = selectSmoothRefreshRate(Build.VERSION.SDK_INT, power.isPowerSaveMode,
            attributes.preferredDisplayModeId, attributes.preferredRefreshRate,
            DisplayRateMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate),
            display.supportedModes.map { DisplayRateMode(it.physicalWidth, it.physicalHeight, it.refreshRate) }) ?: return
        try {
            attributes.preferredRefreshRate = rate
            activity.window.attributes = attributes
            ownedRate = rate
        } catch (_: RuntimeException) {
            attributes.preferredRefreshRate = 0f
            // An OEM/window can decline an optional hint. Normal rendering still continues.
            Log.d("WindowRefresh", "Refresh preference was not accepted for this window")
        }
    }

    fun onPause() {
        resumed = false
        pendingAttach?.let { activity.window.decorView.removeOnAttachStateChangeListener(it) }
        pendingAttach = null
        val rate = ownedRate ?: return
        ownedRate = null
        val attributes = activity.window.attributes
        // Do not overwrite a preference another window owner changed after ours.
        if (attributes.preferredDisplayModeId == 0 && kotlin.math.abs(attributes.preferredRefreshRate - rate) < .01f) {
            try {
                attributes.preferredRefreshRate = 0f
                activity.window.attributes = attributes
            } catch (_: RuntimeException) {
                Log.d("WindowRefresh", "Window was detached before its preference could be restored")
            }
        }
    }
}
