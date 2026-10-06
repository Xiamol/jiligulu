package com.jiligulu.app.ui.littleworld

/** Link traffic is not proof of a usable board. Only a validated initial state ends synchronization. */
internal class RoomHandshakeTimeout {
    private enum class Phase { CLOSED, WAITING_LINK, WAITING_STATE, READY }
    private var phase = Phase.CLOSED
    private var deadline = 0L
    val waitingForState: Boolean get() = phase == Phase.WAITING_STATE
    val active: Boolean get() = phase == Phase.WAITING_LINK || phase == Phase.WAITING_STATE

    fun begin(nowMillis: Long, linkWaitMillis: Long) {
        require(nowMillis >= 0 && linkWaitMillis in 1..300_000)
        phase = Phase.WAITING_LINK
        deadline = nowMillis + linkWaitMillis
    }

    fun linkReady(nowMillis: Long, hosting: Boolean) {
        if (phase != Phase.WAITING_LINK) return
        phase = if (hosting) Phase.READY else Phase.WAITING_STATE
        deadline = if (hosting) 0 else nowMillis + STATE_WAIT_MILLIS
    }

    fun validatedState() {
        if (phase == Phase.WAITING_STATE) { phase = Phase.READY; deadline = 0 }
    }

    fun remaining(nowMillis: Long): Long = if (active) (deadline - nowMillis).coerceAtLeast(0) else 0
    fun expired(nowMillis: Long): Boolean = active && nowMillis >= deadline
    fun close() { phase = Phase.CLOSED; deadline = 0 }
    companion object { const val STATE_WAIT_MILLIS = 8_000L }
}
