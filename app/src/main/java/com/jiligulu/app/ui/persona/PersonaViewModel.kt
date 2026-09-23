package com.jiligulu.app.ui.persona

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.prefs.PendingWater
import com.jiligulu.app.data.reminder.WaterReminderNotifications
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.PersonaEventBus
import com.jiligulu.app.domain.persona.QuipLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 顶部常驻桌宠文案：始终有内容，主动点击即时换句，可见时每 10 秒自动更新。
 * MainScreen 的 STARTED 生命周期负责 start/stop；提醒、问候或手动换句后重新计算展示间隔。
 */
class PersonaViewModel(
    private val app: Application,
    private val prefs: UserPrefs
) : ViewModel() {

    private val engine = PersonaEngine(QuipLibrary.get(app))

    private val bubbleIds = AtomicLong(0)
    private val _bubble = MutableStateFlow(
        BubbleMessage(bubbleIds.getAndIncrement(), DEFAULT_TEXT, BubbleMessage.Kind.IDLE)
    )
    val bubble: StateFlow<BubbleMessage> = _bubble

    // 订阅偏好更新，点击时直接读内存，避免每次互动先等待 DataStore。
    private val displayName = combine(prefs.nickname, prefs.nameSuffix) { nick, suffix ->
        if (nick.isBlank()) "" else nick + suffix
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var idleJob: Job? = null
    private var pendingWater = PendingWater()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _drinkingId = MutableStateFlow<Long?>(null)
    val drinkingId: StateFlow<Long?> = _drinkingId
    private var finishingWater = false

    init {
        engine.markCompanionShown(SystemClock.elapsedRealtime())
        viewModelScope.launch {
            prefs.pendingWater.collect { pending ->
                pendingWater = pending
                if (pending.isPending) {
                    show(pending.text.ifBlank { "水杯准备好啦，点点我，一起喝口水～" }, BubbleMessage.Kind.WATER)
                } else if (_bubble.value.kind == BubbleMessage.Kind.WATER) {
                    show(engine.idleQuipNow(displayName.value, _bubble.value.text) ?: DEFAULT_TEXT, BubbleMessage.Kind.IDLE)
                }
                _ready.value = true
            }
        }
    }

    /** App 打开问候：同时段每日一次；深夜 0-5 点不问候（PRD 拍板） */
    fun onAppOpen() {
        val previousId = _bubble.value.id
        viewModelScope.launch {
            if (prefs.pendingWater.first().isPending) return@launch
            val now = System.currentTimeMillis()
            val slot = engine.greetSlot(now)
            if (slot == "night") return@launch
            val key = "${engine.dayKey(now)}-$slot"
            if (prefs.lastGreetKey.first() == key) return@launch
            // 偏好读取期间用户可能点过桌宠，或刚收到喝水提醒；不覆盖这些更新。
            if (_bubble.value.id != previousId || _bubble.value.kind == BubbleMessage.Kind.WATER) return@launch
            val text = engine.nextGreeting(now, displayName.value) ?: return@launch
            show(text, BubbleMessage.Kind.GREET)
            prefs.setLastGreetKey(key)
        }
    }

    /** 仅在常驻桌宠可见时启动；每次重新可见，当前文案先完整展示一个间隔。 */
    fun startIdleTicker() {
        PersonaEventBus.updateHostVisibility(true)
        if (idleJob?.isActive == true) return
        engine.markCompanionShown(SystemClock.elapsedRealtime())
        idleJob = viewModelScope.launch {
            while (isActive) {
                if (pendingWater.isPending) {
                    delay(PersonaEngine.IDLE_INTERVAL_MS)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val remaining = engine.millisUntilIdleRefresh(now)
                if (remaining > 0L) {
                    delay(remaining)
                    // 等待期间可能发生点击、问候或提醒，需要以最新展示时间重新判断。
                    continue
                }
                val text = engine.nextIdleQuip(now, displayName.value, _bubble.value.text)
                if (text != null) {
                    show(text, BubbleMessage.Kind.IDLE)
                } else {
                    // 台词库为空时也保持正常等待，不能在到期后空转。
                    delay(PersonaEngine.IDLE_INTERVAL_MS)
                }
            }
        }
    }

    fun stopIdleTicker() {
        PersonaEventBus.updateHostVisibility(false)
        idleJob?.cancel()
        idleJob = null
    }

    /** 点桌宠：立即来一条（免节流，点击必须有即时反馈） */
    fun onMascotClick() {
        if (pendingWater.isPending) return
        val text = engine.idleQuipNow(displayName.value, _bubble.value.text) ?: DEFAULT_TEXT
        show(text, BubbleMessage.Kind.IDLE)
    }

    /** 保留旧调用接口：用户确认喝水等交互后换句，常驻区域不会清空。 */
    fun dismissBubble() {
        if (!pendingWater.isPending) onMascotClick()
    }

    fun startDrinking() {
        if (pendingWater.isPending && _drinkingId.value == null) _drinkingId.value = pendingWater.id
    }

    fun cancelDrinking() {
        if (!finishingWater) _drinkingId.value = null
    }

    /** Only a completed animation acknowledges the persisted reminder. */
    fun completeDrinking() {
        val id = _drinkingId.value ?: return
        if (finishingWater) return
        finishingWater = true
        viewModelScope.launch {
            try {
                if (prefs.completeWater(id)) WaterReminderNotifications.cancel(app, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                show("刚才的状态还没存好，点点我再试一次，阿噜。", BubbleMessage.Kind.WATER)
            } finally {
                _drinkingId.value = null
                finishingWater = false
            }
        }
    }

    private fun show(text: String, kind: BubbleMessage.Kind) {
        engine.markCompanionShown(SystemClock.elapsedRealtime())
        _bubble.value = BubbleMessage(bubbleIds.getAndIncrement(), text, kind)
    }

    override fun onCleared() {
        stopIdleTicker()
        super.onCleared()
    }

    companion object {
        private const val DEFAULT_TEXT = "账本准备好啦，点点我，陪你聊两句，阿噜！"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                PersonaViewModel(app, app.container.userPrefs)
            }
        }
    }
}
