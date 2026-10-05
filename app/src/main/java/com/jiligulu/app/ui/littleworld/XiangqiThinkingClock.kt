package com.jiligulu.app.ui.littleworld

/** A friendly local turn reminder, never a rule that changes the game's result. */
data class XiangqiThinkingClock(
    val ply: Int = 0,
    val side: XiangqiSide = XiangqiSide.RED,
    val remainingMillis: Long = TURN_MILLIS,
    val durationMillis: Long = TURN_MILLIS,
) {
    init {
        require(durationMillis in MIN_SECONDS * 1000L..MAX_SECONDS * 1000L)
        require(remainingMillis in 0L..durationMillis)
    }
    val secondsRemaining: Int get() = ((remainingMillis + 999L) / 1000L).toInt()
    val expired: Boolean get() = remainingMillis <= 0

    fun forPosition(game: XiangqiState): XiangqiThinkingClock =
        if (ply == game.ply && side == game.turnSide) this else reset(game, (durationMillis / 1000L).toInt())

    fun elapse(elapsedMillis: Long, active: Boolean): XiangqiThinkingClock =
        if (!active || elapsedMillis <= 0 || expired) this
        else copy(remainingMillis = (remainingMillis - elapsedMillis.coerceAtMost(durationMillis)).coerceAtLeast(0L))

    companion object {
        const val TURN_MILLIS = 120_000L
        const val MIN_SECONDS = 15
        const val MAX_SECONDS = 600
        fun reset(game: XiangqiState, seconds: Int = 120): XiangqiThinkingClock {
            require(seconds in MIN_SECONDS..MAX_SECONDS)
            return XiangqiThinkingClock(game.ply, game.turnSide, seconds * 1000L, seconds * 1000L)
        }
    }
}
