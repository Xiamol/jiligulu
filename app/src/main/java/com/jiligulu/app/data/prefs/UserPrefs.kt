package com.jiligulu.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

data class PendingWater(val id: Long = 0L, val text: String = "") {
    val isPending: Boolean get() = id > 0L
}

/**
 * 用户偏好：称呼、称呼后缀、自定义 API Key。
 * TODO(M5 前)：Key 迁移到 EncryptedSharedPreferences 加密封存（PRD §4）。
 */
class UserPrefs(private val context: Context) {

    companion object {
        private val KEY_NICKNAME = stringPreferencesKey("nickname")
        private val KEY_NAME_SUFFIX = stringPreferencesKey("name_suffix")
        private val KEY_API_KEY = stringPreferencesKey("api_key_override")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_WATER_ENABLED = booleanPreferencesKey("water_enabled")
        private val KEY_WATER_INTERVAL = intPreferencesKey("water_interval_minutes")
        private val KEY_QUIET_START = intPreferencesKey("quiet_start_minutes")
        private val KEY_QUIET_END = intPreferencesKey("quiet_end_minutes")
        private val KEY_LAST_GREET = stringPreferencesKey("last_greet_slot")
        private val KEY_PENDING_WATER = longPreferencesKey("pending_water_id")
        private val KEY_PENDING_WATER_TEXT = stringPreferencesKey("pending_water_text")
        private val KEY_LAST_WATER_ID = longPreferencesKey("last_water_id")
        private val KEY_TYPING_SOUND = booleanPreferencesKey("typing_sound_enabled")
        private val KEY_UPDATE_REPO = stringPreferencesKey("update_repository")
        private val KEY_AUTO_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val KEY_UPDATE_CHECKED_AT = longPreferencesKey("update_checked_at")
        const val DEFAULT_SUFFIX = "大人"

        /** 主题模式：跟随系统 / 强制浅色 / 强制深色 */
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** 喝水提醒默认值：60 分钟一次，免打扰 23:00-08:00（一天内分钟数） */
        const val DEFAULT_WATER_INTERVAL = 60
        const val DEFAULT_QUIET_START = 23 * 60
        const val DEFAULT_QUIET_END = 8 * 60

        /**
         * 内置更新源：未手动设置过时直接使用，无需用户填写。
         * 用户可在「设置 → 应用更新」里改成自己的仓库，或清空以停用检查。
         */
        const val DEFAULT_UPDATE_REPOSITORY = "Xiamol/jiligulu"
    }

    /** null = 还没读过；"" = 未设置（需要 Onboarding） */
    val nickname: Flow<String> = context.dataStore.data.map { it[KEY_NICKNAME] ?: "" }

    val nameSuffix: Flow<String> = context.dataStore.data.map { it[KEY_NAME_SUFFIX] ?: DEFAULT_SUFFIX }

    /** 用户自定义 Key，空串表示用内置默认 */
    val apiKeyOverride: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }

    /** 主题模式：system / light / dark，默认跟随系统 */
    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME_MODE] ?: THEME_SYSTEM }

    /** 喝水提醒开关（默认关） */
    val waterEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WATER_ENABLED] ?: false }

    /** 喝水提醒间隔分钟数（默认 60） */
    val waterIntervalMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_WATER_INTERVAL] ?: DEFAULT_WATER_INTERVAL }

    /** 免打扰开始（一天内分钟数，默认 23:00） */
    val quietStartMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_QUIET_START] ?: DEFAULT_QUIET_START }

    /** 免打扰结束（一天内分钟数，默认 08:00） */
    val quietEndMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_QUIET_END] ?: DEFAULT_QUIET_END }

    /** 上次问候的「日期+时段」键（如 20260921-morning），用于同时段每日一次 */
    val lastGreetKey: Flow<String> = context.dataStore.data.map { it[KEY_LAST_GREET] ?: "" }

    val pendingWater: Flow<PendingWater> = context.dataStore.data.map {
        PendingWater(it[KEY_PENDING_WATER] ?: 0L, it[KEY_PENDING_WATER_TEXT].orEmpty())
    }.distinctUntilChanged()
    val typingSoundEnabled = context.dataStore.data.map { it[KEY_TYPING_SOUND] ?: true }.distinctUntilChanged()
    /**
     * 生效的更新源。用户没手动设置过就用内置默认，开箱即可检查更新。
     * 用户可在「设置 → 应用更新」里改成自己的仓库，或清空以停用检查。
     */
    val updateRepository = context.dataStore.data
        .map { it[KEY_UPDATE_REPO] ?: DEFAULT_UPDATE_REPOSITORY }
        .distinctUntilChanged()

    val autoCheckUpdates = context.dataStore.data.map { it[KEY_AUTO_UPDATES] ?: true }.distinctUntilChanged()
    val updateCheckedAt = context.dataStore.data.map { it[KEY_UPDATE_CHECKED_AT] ?: 0L }

    /** Save before posting a notification. Repeated reminders preserve the outstanding cup. */
    suspend fun markWaterDue(now: Long, text: String): PendingWater {
        var pending = PendingWater()
        context.dataStore.edit { values ->
            if (values[KEY_WATER_ENABLED] != true) return@edit
            val existing = values[KEY_PENDING_WATER] ?: 0L
            val id = if (existing > 0L) existing else maxOf(now, (values[KEY_LAST_WATER_ID] ?: 0L) + 1L)
            if (existing == 0L) {
                values[KEY_PENDING_WATER] = id
                values[KEY_LAST_WATER_ID] = id
                values[KEY_PENDING_WATER_TEXT] = text
            }
            pending = PendingWater(id, values[KEY_PENDING_WATER_TEXT].orEmpty())
        }
        return pending
    }

    /** An old animation cannot acknowledge a newer reminder. */
    suspend fun completeWater(id: Long): Boolean {
        var completed = false
        context.dataStore.edit {
            if (id > 0L && it[KEY_PENDING_WATER] == id) {
                it.remove(KEY_PENDING_WATER)
                it.remove(KEY_PENDING_WATER_TEXT)
                completed = true
            }
        }
        return completed
    }

    suspend fun setTypingSoundEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_TYPING_SOUND] = enabled } }
    suspend fun setUpdateRepository(repository: String) {
        context.dataStore.edit { it[KEY_UPDATE_REPO] = repository; it.remove(KEY_UPDATE_CHECKED_AT) }
    }
    suspend fun setAutoCheckUpdates(enabled: Boolean) { context.dataStore.edit { it[KEY_AUTO_UPDATES] = enabled } }
    suspend fun setUpdateCheckedAt(now: Long) { context.dataStore.edit { it[KEY_UPDATE_CHECKED_AT] = now } }

    suspend fun setNickname(value: String) {
        context.dataStore.edit { it[KEY_NICKNAME] = value.trim() }
    }

    suspend fun setNameSuffix(value: String) {
        context.dataStore.edit { it[KEY_NAME_SUFFIX] = value.trim().ifBlank { DEFAULT_SUFFIX } }
    }

    suspend fun setApiKeyOverride(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value.trim() }
    }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = value }
    }

    suspend fun setWaterEnabled(value: Boolean) {
        context.dataStore.edit {
            it[KEY_WATER_ENABLED] = value
            if (!value) { it.remove(KEY_PENDING_WATER); it.remove(KEY_PENDING_WATER_TEXT) }
        }
    }

    suspend fun setWaterIntervalMinutes(value: Int) {
        context.dataStore.edit { it[KEY_WATER_INTERVAL] = value }
    }

    suspend fun setQuietHours(startMinutes: Int, endMinutes: Int) {
        context.dataStore.edit {
            it[KEY_QUIET_START] = startMinutes
            it[KEY_QUIET_END] = endMinutes
        }
    }

    suspend fun setLastGreetKey(value: String) {
        context.dataStore.edit { it[KEY_LAST_GREET] = value }
    }
}
