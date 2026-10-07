package com.jiligulu.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.statsDisplayStore by preferencesDataStore(name = "stats_display")

enum class StatsBarMode(val key: String, val label: String) {
    COMPACT_TEN_DAYS("ten_days", "10 天紧凑"),
    MONTH_COMPRESSED("whole_month", "整月压缩");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: COMPACT_TEN_DAYS
    }
}

/** A display preference only; changing it never changes or truncates the ledger. */
class StatsDisplayPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.statsDisplayStore)

    val barMode = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { StatsBarMode.fromKey(it[KEY_BAR_MODE]) }.distinctUntilChanged()

    suspend fun setBarMode(mode: StatsBarMode) {
        store.edit { it[KEY_BAR_MODE] = mode.key }
    }

    private companion object { val KEY_BAR_MODE = stringPreferencesKey("bar_mode") }
}
