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

private val Context.billContextStore by preferencesDataStore(name = "bill_context")

/** Time range for conversation context; exact ledger queries use the complete database. */
enum class BillContextWindow(val key: String, val label: String, val windowDays: Int?, val maxBillCount: Int) {
    THREE_DAYS("three_days", "3天", 3, 30),
    SEVEN_DAYS("seven_days", "7天", 7, 100),
    THIRTY_DAYS("thirty_days", "30天", 30, 300),
    NINETY_DAYS("ninety_days", "90天", 90, 600),
    ALL("all", "全部", null, 1000);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: THREE_DAYS
    }
}

class BillContextPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.billContextStore)

    val window = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { BillContextWindow.fromKey(it[KEY_WINDOW]) }.distinctUntilChanged()

    suspend fun setWindow(window: BillContextWindow) { store.edit { it[KEY_WINDOW] = window.key } }

    private companion object { val KEY_WINDOW = stringPreferencesKey("window") }
}
