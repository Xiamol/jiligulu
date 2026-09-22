package com.jiligulu.app.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.reminder.WaterReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsUiState(
    val isLoading: Boolean = true,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val nickname: String = "",
    val suffix: String = "",
    val apiKey: String = "",
    val themeMode: String = UserPrefs.THEME_SYSTEM,
    val waterEnabled: Boolean = false,
    val waterInterval: Int = UserPrefs.DEFAULT_WATER_INTERVAL,
    val quietStartText: String = "",
    val quietEndText: String = "",
    val error: String? = null
) {
    val quietStartInvalid: Boolean get() = isLoaded && textToMinutes(quietStartText) == null
    val quietEndInvalid: Boolean get() = isLoaded && textToMinutes(quietEndText) == null
}

/** Owns the editable draft and persistence; the screen only renders state and requests permissions. */
class SettingsViewModel(
    private val app: Application,
    private val prefs: UserPrefs
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val savedEvents = Channel<Unit>(Channel.BUFFERED)
    val saved = savedEvents.receiveAsFlow()

    // Keep rapid preference changes and the final save in the same order as user actions.
    private val writeMutex = Mutex()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                // Populate the draft only from real DataStore emissions, never stateIn placeholders.
                _uiState.value = SettingsUiState(
                    isLoading = false,
                    isLoaded = true,
                    nickname = prefs.nickname.first(),
                    suffix = prefs.nameSuffix.first(),
                    apiKey = prefs.apiKeyOverride.first(),
                    themeMode = prefs.themeMode.first(),
                    waterEnabled = prefs.waterEnabled.first(),
                    waterInterval = prefs.waterIntervalMinutes.first(),
                    quietStartText = minutesToText(prefs.quietStartMinutes.first()),
                    quietEndText = minutesToText(prefs.quietEndMinutes.first())
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "设置读取失败，请重试。") }
            }
        }
    }

    fun setNickname(value: String) = editDraft { it.copy(nickname = value) }
    fun setSuffix(value: String) = editDraft { it.copy(suffix = value) }
    fun setApiKey(value: String) = editDraft { it.copy(apiKey = value) }

    fun setThemeMode(mode: String) {
        if (mode !in listOf(UserPrefs.THEME_SYSTEM, UserPrefs.THEME_LIGHT, UserPrefs.THEME_DARK)) return
        writePreference {
            prefs.setThemeMode(mode)
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    fun setWaterEnabled(enabled: Boolean) {
        writePreference {
            prefs.setWaterEnabled(enabled)
            if (enabled) {
                WaterReminderScheduler.schedule(app, prefs.waterIntervalMinutes.first())
            } else {
                WaterReminderScheduler.cancel(app)
            }
            _uiState.update { it.copy(waterEnabled = enabled) }
        }
    }

    fun setWaterInterval(minutes: Int) {
        // 不再用白名单卡死取值：1 分钟到 12 小时 59 分随意选。
        // （原实现只放行 [15,30,45,60,90,120]，其余值静默 return，
        //   表现为「选了时间点确认完全没反应」。）
        if (minutes < MIN_WATER_INTERVAL) return
        writePreference {
            prefs.setWaterIntervalMinutes(minutes)
            if (prefs.waterEnabled.first()) {
                WaterReminderScheduler.schedule(app, minutes)
            }
            _uiState.update { it.copy(waterInterval = minutes) }
        }
    }

    fun setQuietStart(value: String) {
        editDraft { it.copy(quietStartText = value) }
        persistQuietHours()
    }

    fun setQuietEnd(value: String) {
        editDraft { it.copy(quietEndText = value) }
        persistQuietHours()
    }

    private fun persistQuietHours() {
        val state = _uiState.value
        val start = textToMinutes(state.quietStartText) ?: return
        val end = textToMinutes(state.quietEndText) ?: return
        writePreference { prefs.setQuietHours(start, end) }
    }

    fun save() {
        val draft = _uiState.value
        if (!draft.isLoaded || draft.isSaving) return
        if (draft.waterEnabled && (draft.quietStartInvalid || draft.quietEndInvalid)) {
            _uiState.update { it.copy(error = "请把免打扰时间填写为有效的 HH:mm 格式。") }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    prefs.setNickname(draft.nickname)
                    prefs.setNameSuffix(draft.suffix)
                    prefs.setApiKeyOverride(draft.apiKey)
                    val start = textToMinutes(draft.quietStartText)
                    val end = textToMinutes(draft.quietEndText)
                    if (start != null && end != null) prefs.setQuietHours(start, end)
                }
                savedEvents.send(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "保存失败，请重试。") }
            }
        }
    }

    private fun editDraft(transform: (SettingsUiState) -> SettingsUiState) {
        if (!_uiState.value.isLoaded || _uiState.value.isSaving) return
        _uiState.update { transform(it).copy(error = null) }
    }

    private fun writePreference(action: suspend () -> Unit) {
        if (!_uiState.value.isLoaded || _uiState.value.isSaving) return
        viewModelScope.launch {
            try {
                writeMutex.withLock { action() }
                _uiState.update { it.copy(error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(error = "这项设置未能保存，请重试。") }
            }
        }
    }

    companion object {
        /** 下界取 1 分钟：WorkManager 的周期任务实际最小间隔由系统决定，这里只管业务下限。 */
        const val MIN_WATER_INTERVAL = 1
        const val MAX_WATER_INTERVAL = 12 * 60 + 59

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                SettingsViewModel(app, app.container.userPrefs)
            }
        }
    }
}

private fun minutesToText(minutes: Int): String =
    "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)

internal fun textToMinutes(text: String): Int? {
    val match = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(text.trim()) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}
