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

enum class StatsBarMode(val key: String, val label: String, val compactDays: Int?) {
    COMPACT_FIVE_DAYS("five_days", "5天", 5),
    COMPACT_SEVEN_DAYS("seven_days", "7天", 7),
    COMPACT_TEN_DAYS("ten_days", "10天", 10),
    MONTH_COMPRESSED("whole_month", "整月", null);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: MONTH_COMPRESSED
    }
}

enum class CalendarProgressMode(val key: String) { MONTH("month"), YEAR("year");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: MONTH }
}

/** A display preference only; changing it never changes or truncates the ledger. */
class StatsDisplayPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.statsDisplayStore)

    private val preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    val barMode = preferences.map { StatsBarMode.fromKey(it[KEY_BAR_MODE]) }.distinctUntilChanged()

    suspend fun setBarMode(mode: StatsBarMode) {
        store.edit { it[KEY_BAR_MODE] = mode.key }
    }

    val calendarProgressMode = preferences.map { CalendarProgressMode.fromKey(it[KEY_PROGRESS_MODE]) }.distinctUntilChanged()
    suspend fun setCalendarProgressMode(mode: CalendarProgressMode) { store.edit { it[KEY_PROGRESS_MODE] = mode.key } }

    private companion object {
        val KEY_BAR_MODE = stringPreferencesKey("bar_mode")
        val KEY_PROGRESS_MODE = stringPreferencesKey("calendar_progress_mode")
    }
}
