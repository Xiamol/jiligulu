package com.jiligulu.app.domain.persona

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * WorkManager Worker → 可见的常驻桌宠；二级页面没有宿主，仍使用系统通知。
 */
object PersonaEventBus {

    sealed interface Event {
        data object WaterTick : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    @Volatile
    var isHostVisible: Boolean = false
        private set

    fun updateHostVisibility(visible: Boolean) {
        isHostVisible = visible
    }

    /** 无可见订阅者或队列已满时返回 false，让 Worker 回退到通知。 */
    fun emit(event: Event): Boolean =
        isHostVisible && _events.subscriptionCount.value > 0 && _events.tryEmit(event)
}
