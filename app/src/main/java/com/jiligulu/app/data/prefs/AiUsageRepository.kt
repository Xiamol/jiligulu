package com.jiligulu.app.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jiligulu.app.core.ai.AiTokenUsage
import com.jiligulu.app.core.ai.AiUsageSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import com.jiligulu.app.core.ai.*

private val Context.aiUsageStore by preferencesDataStore(name = "ai_usage")

class AiUsageRepository internal constructor(private val store: DataStore<Preferences>, private val database: AiUsageDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis) : AiUsageMeter {
    constructor(context: Context) : this(context.applicationContext.aiUsageStore, AiUsageDatabase(context.applicationContext))
    private val key = stringPreferencesKey("aggregate_v1")
    private val json = Json { ignoreUnknownKeys = true }
    val snapshots = store.data.map { decode(it[key]) }
    private val revision = MutableStateFlow(0L)
    private val migration = Mutex()
    val revisions get() = revision
    suspend fun configuredPrice(profile: AiProviderProfile): AiPriceSnapshot? = withContext(Dispatchers.IO) { database.price(profile) }
    suspend fun setPrice(profile: AiProviderProfile, price: AiPriceSnapshot) = withContext(Dispatchers.IO) {
        database.savePrice(profile, price); revision.update { it + 1 }
    }
    override suspend fun begin(profile: AiProviderProfile, purpose: AiUsagePurpose): AiUsageTicket = withContext(Dispatchers.IO) {
        migrateLegacy()
        AiUsageTicket.start(profile, purpose, database.price(profile), nowMillis())
    }
    override suspend fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?): Unit = withContext(NonCancellable + Dispatchers.IO) {
        try { if (database.finish(ticket, usage)) revision.update { it + 1 } }
        catch (_: Exception) { Log.w("AiUsage", "Local request metadata could not be saved") }
        Unit
    }
    suspend fun daily(start: LocalDate, end: LocalDate, group: String? = null, providerKey: String? = null): List<AiCostGroup> =
        withContext(Dispatchers.IO) { migrateLegacy(); database.daily(start.toString(), end.toString(), group, providerKey) }
    suspend fun total(group: String? = null, providerKey: String? = null): List<AiCostGroup> =
        withContext(Dispatchers.IO) { migrateLegacy(); database.total(group, providerKey) }
    suspend fun details(day: LocalDate, group: String? = null, providerKey: String? = null): List<AiCostGroup> =
        withContext(Dispatchers.IO) { migrateLegacy(); database.details(day.toString(), group, providerKey) }
    private suspend fun migrateLegacy() = migration.withLock {
        if (database.legacyMigrated()) return@withLock
        val known = listOf("deepseek", "deepseek:${AiConfig.DEEPSEEK_PRO_MODEL}", "custom")
            .associateBy { keyFor(it).name }
        val old = store.data.first().asMap().mapNotNull { (storedKey, value) ->
            if (storedKey.name != key.name && !storedKey.name.startsWith("provider_")) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val snapshot = runCatching { json.decodeFromString(AiUsageSnapshot.serializer(), raw) }.getOrNull() ?: return@mapNotNull null
            (known[storedKey.name] ?: storedKey.name) to snapshot
        }
        database.migrateLegacy(old)
    }
    fun snapshotsFor(providerId: String) = store.data.map { decode(it[keyFor(providerId)]) }

    private fun keyFor(providerId: String) = if (providerId == "deepseek") key else stringPreferencesKey("provider_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(providerId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) })

    /** Called once per completed/failed HTTP attempt, before parsing model content. */
    suspend fun record(usage: AiTokenUsage?) = record(usage, "deepseek")
    suspend fun record(usage: AiTokenUsage?, providerId: String) = withContext(NonCancellable) {
        try {
            val day = LocalDate.now().toString()
            val selectedKey = keyFor(providerId)
            store.edit { prefs ->
                prefs[selectedKey] = json.encodeToString(AiUsageSnapshot.serializer(), decode(prefs[selectedKey]).add(day, usage))
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
