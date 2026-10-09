package com.jiligulu.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiEndpointKind
import com.jiligulu.app.core.ai.AiProviderConnection
import com.jiligulu.app.core.ai.AiProviderEndpoint
import com.jiligulu.app.core.ai.AiProviderId
import com.jiligulu.app.core.ai.AiProviderProfile
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.aiProviderStore by preferencesDataStore(name = "ai_providers")

data class AiProviderState(
    val selected: AiProviderId = AiProviderId.DEEPSEEK,
    val deepSeekModel: String = AiConfig.MODEL,
    val custom: AiProviderProfile = AiProviderProfile.custom(),
    val hasDeepSeekKey: Boolean = false,
    val hasCustomKey: Boolean = false,
) {
    val selectedProfile: AiProviderProfile get() = if (selected == AiProviderId.DEEPSEEK) AiProviderProfile.deepSeek(deepSeekModel) else custom
}

/** Separate provider settings; migration never modifies UserPrefs or the build configuration. */
class AiProviderPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.aiProviderStore)
    private val selectedKey = stringPreferencesKey("selected")
    private val modelKey = stringPreferencesKey("deepseek_model")
    private val deepSeekKey = stringPreferencesKey("deepseek_key")
    private val customKey = stringPreferencesKey("custom_key")
    private val customProfile = stringPreferencesKey("custom_profile")
    private val migrated = booleanPreferencesKey("legacy_key_migrated")
    private val json = Json { ignoreUnknownKeys = true }

    val state = store.data.map(::decode).distinctUntilChanged()
    private fun decode(prefs: Preferences) = AiProviderState(
        selected = runCatching { AiProviderId.valueOf(prefs[selectedKey].orEmpty()) }.getOrDefault(AiProviderId.DEEPSEEK),
        deepSeekModel = prefs[modelKey]?.takeIf { it.isNotBlank() } ?: AiConfig.MODEL,
        custom = prefs[customProfile]?.let { runCatching { json.decodeFromString(AiProviderProfile.serializer(), it) }.getOrNull() }
            ?.copy(id = AiProviderId.CUSTOM) ?: AiProviderProfile.custom(),
        hasDeepSeekKey = !prefs[deepSeekKey].isNullOrBlank(), hasCustomKey = !prefs[customKey].isNullOrBlank(),
    )

    suspend fun migrateLegacyDeepSeekKey(legacyKey: String) {
        store.edit { prefs ->
            if (prefs[migrated] != true) {
                if (prefs[deepSeekKey] == null) prefs[deepSeekKey] = legacyKey.trim()
                prefs[migrated] = true
            }
        }
    }

    suspend fun select(id: AiProviderId) { store.edit { it[selectedKey] = id.name } }
    suspend fun setDeepSeekModel(model: String) {
        require(model in setOf(AiConfig.MODEL, AiConfig.DEEPSEEK_PRO_MODEL)) { "请选择可用的 DeepSeek 模型" }
        store.edit { it[modelKey] = model }
    }

    suspend fun savedKey(id: AiProviderId): String = store.data.first()[if (id == AiProviderId.DEEPSEEK) deepSeekKey else customKey].orEmpty()

    suspend fun saveDeepSeekKey(value: String) {
        validateKey(value)
        store.edit { it[deepSeekKey] = value.trim(); it[migrated] = true }
    }

    suspend fun saveCustom(profile: AiProviderProfile, key: String) {
        val normalized = profile.copy(id = AiProviderId.CUSTOM, name = profile.name.trim(),
            address = profile.address.trim(), model = profile.model.trim())
        AiProviderEndpoint.validate(normalized)
        validateKey(key)
        store.edit { prefs ->
            prefs[customProfile] = json.encodeToString(AiProviderProfile.serializer(), normalized)
            prefs[customKey] = key.trim()
        }
    }

    suspend fun connection(legacyDeepSeekKey: String): AiProviderConnection {
        migrateLegacyDeepSeekKey(legacyDeepSeekKey)
        val prefs = store.data.first()
        val current = decode(prefs)
        val profile = current.selectedProfile
        AiProviderEndpoint.validate(profile)
        val key = prefs[if (current.selected == AiProviderId.DEEPSEEK) deepSeekKey else customKey].orEmpty()
        if (current.selected == AiProviderId.DEEPSEEK && key.isBlank()) {
            val defaultProfile = profile.copy(address = AiConfig.DEFAULT_SERVICE_URL, endpointKind = AiEndpointKind.CHAT_ENDPOINT)
            AiProviderEndpoint.validate(defaultProfile)
            return AiProviderConnection(defaultProfile, "")
        }
        return AiProviderConnection(profile, key)
    }

    private fun validateKey(value: String) {
        require(value.trim().none { it.isISOControl() }) { "密钥不能包含换行或控制字符" }
    }
}
