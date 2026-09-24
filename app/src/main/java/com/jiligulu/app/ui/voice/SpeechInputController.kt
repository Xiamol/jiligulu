package com.jiligulu.app.ui.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SpeechInputEngine {
    interface Listener {
        fun ready()
        fun partial(text: String)
        fun result(text: String)
        fun error(message: String)
    }
    fun start(listener: Listener)
    fun stop()
    fun cancel()
    fun destroy()
}

enum class VoicePhase { IDLE, LISTENING, PROCESSING, CAPTURED }
data class VoiceResult(val id: Long, val text: String)
data class VoiceInputState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val partial: String = "",
    val result: VoiceResult? = null,
    val error: String? = null,
    val session: Long = 0
) { val busy: Boolean get() = phase != VoicePhase.IDLE }

/** Main-thread state machine. Cancellation invalidates all callbacks, including late server results. */
class SpeechInputController(private val factory: () -> SpeechInputEngine) {
    private val mutable = MutableStateFlow(VoiceInputState())
    val state = mutable.asStateFlow()
    private var engine: SpeechInputEngine? = null
    private var generation = 0L
    private var ready = false
    private var released = false
    private var captured: String? = null

    fun start() {
        if (mutable.value.busy) return
        val token = ++generation
        ready = false; released = false; captured = null
        mutable.value = VoiceInputState(phase = VoicePhase.LISTENING, session = token)
        try {
            engine = factory()
            engine!!.start(object : SpeechInputEngine.Listener {
                override fun ready() {
                    if (token != generation) return
                    ready = true
                    if (released) stopEngine()
                }
                override fun partial(text: String) {
                    if (token == generation) mutable.value = mutable.value.copy(partial = text.take(2000))
                }
                override fun result(text: String) {
                    if (token != generation) return
                    val cleaned = text.trim().take(2000)
                    if (cleaned.isBlank()) { fail("没听清，再按住说一次吧。"); return }
                    captured = cleaned
                    if (released) deliver(cleaned)
                    else mutable.value = mutable.value.copy(phase = VoicePhase.CAPTURED, partial = cleaned)
                }
                override fun error(message: String) {
                    if (token == generation) fail(message)
                }
            })
        } catch (_: SecurityException) { fail("需要麦克风权限才能说话。")
        } catch (_: Exception) { fail("语音引擎暂时没准备好，请稍后再试或切回键盘。") }
    }

    fun stop() {
        if (!mutable.value.busy || released) return
        released = true
        captured?.let { deliver(it); return }
        mutable.value = mutable.value.copy(phase = VoicePhase.PROCESSING)
        if (ready) stopEngine() // A fast release can precede onReadyForSpeech.
    }

    private fun stopEngine() {
        try { engine?.stop() } catch (_: Exception) { fail("这次语音没能完成，请重试。") }
    }

    fun timeout(session: Long) {
        if (session == generation && mutable.value.busy) fail("语音识别等待太久了，请重试。")
    }
    fun showError(message: String) { if (!mutable.value.busy) mutable.value = mutable.value.copy(error = message) }
    fun consumeResult(id: Long) {
        if (mutable.value.result?.id == id) mutable.value = mutable.value.copy(result = null)
    }
    fun cancel() {
        releaseEngine(cancel = true)
        mutable.value = VoiceInputState(session = generation)
    }
    private fun deliver(text: String) {
        val id = generation
        releaseEngine(cancel = false)
        mutable.value = VoiceInputState(result = VoiceResult(id, text), session = generation)
    }
    private fun fail(message: String) {
        releaseEngine(cancel = true)
        mutable.value = VoiceInputState(error = message, session = generation)
    }
    private fun releaseEngine(cancel: Boolean) {
        generation++
        val previous = engine; engine = null
        if (cancel) runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        captured = null
    }
}
