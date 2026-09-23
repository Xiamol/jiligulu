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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

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
            // 可选维护并行且各有上限，系统调度/清理变慢不能挡住已经准备好的账本。
            // withTimeoutOrNull 仅吞自己的超时；Activity 销毁造成的取消继续向上传播。
            coroutineScope {
                awaitAll(
                    async {
                        withTimeoutOrNull(1_000L) {
                            TrashCleaner.purgeExpired(container.billRepository, container.userPrefs)
                        }
                    },
                    async {
                        withTimeoutOrNull(1_000L) {
                            container.migrateWaterScheduleIfNeeded()
                            container.catchUpWaterReminder()
                        }
                    }
                )
            }
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
