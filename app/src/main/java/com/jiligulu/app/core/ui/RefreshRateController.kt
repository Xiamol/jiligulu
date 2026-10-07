package com.jiligulu.app.core.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jiligulu.app.data.prefs.AppRefreshRate
import com.jiligulu.app.data.prefs.DisplayPerformancePrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.WeakHashMap

internal data class AppWindowRefreshHint(val modeId: Int, val refreshRate: Float)

/** Main-thread registry; a dialog mirrors only its own Activity, never a global display. */
internal object AppWindowRefreshHints {
    private val windows = WeakHashMap<Window, MutableStateFlow<AppWindowRefreshHint?>>()
    fun forWindow(window: Window): StateFlow<AppWindowRefreshHint?> =
        windows.getOrPut(window) { MutableStateFlow(null) }
    fun publish(window: Window, hint: AppWindowRefreshHint?) {
        windows.getOrPut(window) { MutableStateFlow(null) }.value = hint
    }
}

internal data class DisplayRateMode(val width: Int, val height: Int, val refreshRate: Float, val id: Int = 0)

/** An unsupported 90 Hz request may fall back to 60 Hz, never silently rise to 120 Hz. */
internal fun selectAppRefreshMode(
    preference: AppRefreshRate,
    powerSave: Boolean,
    current: DisplayRateMode,
    supported: List<DisplayRateMode>
): DisplayRateMode? {
    if (preference == AppRefreshRate.SYSTEM || current.width <= 0 || current.height <= 0) return null
    val ceiling = if (powerSave) minOf(preference.hertz, 60) else preference.hertz
    return supported.asSequence().filter {
        it.width == current.width && it.height == current.height && it.refreshRate.isFinite() &&
            it.refreshRate > 0f && it.refreshRate <= ceiling + .5f
    }.maxWithOrNull(compareBy<DisplayRateMode> { it.refreshRate }.thenBy { if (it.id == current.id) 1 else 0 })
}

/**
 * Owns only the resumed Activity's refresh hint. Default SYSTEM does not boost to the
 * highest mode. Display/resolution changes re-evaluate supported modes; pause restores
 * the previous window attributes without changing any global or another owner's setting.
 */
internal class RefreshRateController(private val activity: ComponentActivity) {
    private val prefs = DisplayPerformancePrefs(activity)
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private val power = activity.getSystemService(PowerManager::class.java)
    private var preference = AppRefreshRate.SYSTEM
    private var resumed = false
    private var collection: Job? = null
    private var baseline: Pair<Int, Float>? = null
    private var applied: Pair<Int, Float>? = null
    private var displayListening = false
    private var receiverRegistered = false
    private var pendingAttach: View.OnAttachStateChangeListener? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (activity.window.decorView.display?.displayId == displayId) apply()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { apply() }
    }

    fun onResume() {
        if (resumed) return
        resumed = true
        baseline = activity.window.attributes.let { it.preferredDisplayModeId to it.preferredRefreshRate }
        updateMonitoring()
        collection = activity.lifecycleScope.launch {
            prefs.refreshRate.collect { preference = it; updateMonitoring(); apply() }
        }
        val decor = activity.window.decorView
        if (decor.isAttachedToWindow) apply() else {
            pendingAttach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    pendingAttach = null
                    apply()
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            }.also(decor::addOnAttachStateChangeListener)
        }
    }

    private fun updateMonitoring() {
        if (!resumed || preference == AppRefreshRate.SYSTEM) { stopMonitoring(); return }
        if (!displayListening) manager?.let {
            runCatching { it.registerDisplayListener(displayListener, Handler(Looper.getMainLooper())) }
                .onSuccess { displayListening = true }
                .onFailure { Log.d("WindowRefresh", "Display changes cannot be observed on this device") }
        }
        if (!receiverRegistered) runCatching {
            ContextCompat.registerReceiver(activity, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { receiverRegistered = true }
            .onFailure { Log.d("WindowRefresh", "Power changes will be checked when the window resumes") }
    }

    private fun stopMonitoring() {
        if (displayListening) { manager?.unregisterDisplayListener(displayListener); displayListening = false }
        if (receiverRegistered) { runCatching { activity.unregisterReceiver(receiver) }; receiverRegistered = false }
    }

    private fun apply() {
        if (!resumed || !activity.window.decorView.isAttachedToWindow) return
        // SYSTEM must have no display-mode dependency at all. Some virtual/OEM displays
        // cannot expose mode tables; an optional refresh preference must never break launch.
        val mode = if (preference == AppRefreshRate.SYSTEM) null else runCatching {
            val display = activity.window.decorView.display ?: return@runCatching null
            val current = display.mode
            selectAppRefreshMode(preference, power?.isPowerSaveMode == true,
                DisplayRateMode(current.physicalWidth, current.physicalHeight, current.refreshRate, current.modeId),
                display.supportedModes.map { DisplayRateMode(it.physicalWidth, it.physicalHeight, it.refreshRate, it.modeId) })
        }.getOrNull()
        val target = mode?.let { it.id to it.refreshRate } ?: baseline ?: return
        val attributes = activity.window.attributes
        val existing = attributes.preferredDisplayModeId to attributes.preferredRefreshRate
        // A different owner may have set a special mode after ours; don't overwrite it.
        if (existing != applied && existing != baseline) {
            AppWindowRefreshHints.publish(activity.window, null)
            return
        }
        if (existing == target) {
            AppWindowRefreshHints.publish(activity.window, mode?.let { AppWindowRefreshHint(it.id, it.refreshRate) })
            return
        }
        try {
            attributes.preferredDisplayModeId = target.first
            attributes.preferredRefreshRate = target.second
            activity.window.attributes = attributes
            applied = if (target == baseline) null else target
            AppWindowRefreshHints.publish(activity.window, mode?.let { AppWindowRefreshHint(it.id, it.refreshRate) })
        } catch (_: RuntimeException) {
            AppWindowRefreshHints.publish(activity.window, null)
            Log.d("WindowRefresh", "The device declined this window's refresh preference")
        }
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        AppWindowRefreshHints.publish(activity.window, null)
        collection?.cancel(); collection = null
        stopMonitoring()
        pendingAttach?.let(activity.window.decorView::removeOnAttachStateChangeListener)
        pendingAttach = null
        val owned = applied
        val original = baseline
        applied = null; baseline = null
        if (owned == null || original == null) return
        val attributes = activity.window.attributes
        if ((attributes.preferredDisplayModeId to attributes.preferredRefreshRate) == owned) runCatching {
            attributes.preferredDisplayModeId = original.first
            attributes.preferredRefreshRate = original.second
            activity.window.attributes = attributes
        }
    }
}
