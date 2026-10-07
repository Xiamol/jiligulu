package com.jiligulu.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.globalGlassStore by preferencesDataStore(name = "global_glass")

enum class GlobalGlassFrameRate(val fps: Int, val label: String) {
    FPS_15(15, "15"), FPS_30(30, "30"), FPS_60(60, "60"), FPS_120(120, "120");
    val intervalNanos: Long get() = 1_000_000_000L / fps
    val intervalMillis: Long get() = (1000L + fps - 1L) / fps
    companion object {
        val DEFAULT = FPS_30
        fun fromFps(fps: Int?) = entries.firstOrNull { it.fps == fps } ?: DEFAULT
    }
}

/** A rendering target only: neither changing it nor restoring it grants screen sharing. */
class GlobalGlassPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.globalGlassStore)
    val frameRate = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { GlobalGlassFrameRate.fromFps(it[KEY_FRAME_RATE]) }.distinctUntilChanged()
    suspend fun setFrameRate(rate: GlobalGlassFrameRate) { store.edit { it[KEY_FRAME_RATE] = rate.fps } }
    private companion object { val KEY_FRAME_RATE = intPreferencesKey("sampling_target_fps") }
}
