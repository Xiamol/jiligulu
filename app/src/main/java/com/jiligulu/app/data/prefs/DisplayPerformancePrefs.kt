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

private val Context.displayPerformanceStore by preferencesDataStore(name = "display_performance")

/** Stored caps are app-window hints, never changes to the phone's display settings. */
enum class AppRefreshRate(val hertz: Int, val label: String) {
    SYSTEM(0, "系统"), HZ_60(60, "60"), HZ_90(90, "90"), HZ_120(120, "120");

    companion object {
        fun fromHertz(hertz: Int?) = entries.firstOrNull { it.hertz == hertz } ?: SYSTEM
    }
}

class DisplayPerformancePrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.displayPerformanceStore)
    val refreshRate = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { AppRefreshRate.fromHertz(it[KEY_REFRESH_RATE]) }.distinctUntilChanged()

    suspend fun setRefreshRate(rate: AppRefreshRate) {
        store.edit { it[KEY_REFRESH_RATE] = rate.hertz }
    }

    private companion object {
        val KEY_REFRESH_RATE = intPreferencesKey("app_refresh_rate")
    }
}
