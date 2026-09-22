package com.jiligulu.app.ui.startup

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiligulu.app.AppContainer
import com.jiligulu.app.data.repository.TrashCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StartupState(val nickname: String? = null, val prepared: Boolean = false,
    val ready: Boolean = false, val error: String? = null)

class StartupViewModel(
    private val loadData: suspend () -> String,
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
    private val onStartupFinished: () -> Unit = {}
) : ViewModel() {
    constructor(container: AppContainer) : this(
        loadData = {
            val nickname = container.userPrefs.nickname.first()
            container.preloadLedger()
            container.userPrefs.pendingWater.first()
            // 顺手清掉过期的回收站账单。不是时间敏感的事，冷启动扫一遍就够，
            // 不值得为它单独养一个 WorkManager 任务。失败不影响启动。
            TrashCleaner.purgeExpired(container.billRepository, container.userPrefs)
            // 一次性把喝水提醒换成自链调度（含清理 0.5.4 遗留的周期任务）。
            // 内部有版本号闸门，迁移过就是一次 DataStore 读，不会重置提醒倒计时。
            container.migrateWaterScheduleIfNeeded()
            nickname
        },
        onStartupFinished = { container.startupCompleted = true }
    )
    private val _state = MutableStateFlow(StartupState())
    val state = _state.asStateFlow()
    private var load: Job? = null
    private var entry = 0L

    fun enter(): Long {
        entry += 1L
        prepare()
        return entry
    }

    fun prepare() {
        load?.cancel()
        _state.value = _state.value.copy(ready = false, error = null)
        load = viewModelScope.launch {
            val started = elapsedTime()
            try {
                val nickname = loadData()
                _state.value = StartupState(nickname = nickname, prepared = true)
                delay((650L - (elapsedTime() - started)).coerceAtLeast(0L))
                _state.value = StartupState(nickname = nickname, prepared = true, ready = true)
                onStartupFinished()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "账本暂时没准备好，请重试。原有记录仍会保留。")
            }
        }
    }

    fun pause(ownerEntry: Long) {
        // A disposed old Activity must not cancel the new Activity's preparation after rotation.
        if (ownerEntry == entry) load?.cancel()
    }
}
