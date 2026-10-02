package com.jiligulu.app.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jiligulu.app.core.ai.AiTokenUsage
import com.jiligulu.app.core.ai.AiUsageSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.aiUsageStore by preferencesDataStore(name = "ai_usage")

class AiUsageRepository(context: Context) {
    private val store = context.applicationContext.aiUsageStore
    private val key = stringPreferencesKey("aggregate_v1")
    private val json = Json { ignoreUnknownKeys = true }
    val snapshots = store.data.map { decode(it[key]) }

    /** Called once per completed/failed HTTP attempt, before parsing model content. */
    suspend fun record(usage: AiTokenUsage?) = withContext(NonCancellable) {
        try {
            val day = LocalDate.now().toString()
            store.edit { prefs ->
                prefs[key] = json.encodeToString(AiUsageSnapshot.serializer(), decode(prefs[key]).add(day, usage))
            }
        } catch (_: Exception) {
            // Meter persistence can never turn a valid reply into a retry (and another charge).
            Log.w("AiUsage", "Local usage counter could not be saved")
        }
    }

    private fun decode(value: String?): AiUsageSnapshot = value?.let {
        runCatching { json.decodeFromString(AiUsageSnapshot.serializer(), it) }.getOrNull()
    } ?: AiUsageSnapshot()
}
