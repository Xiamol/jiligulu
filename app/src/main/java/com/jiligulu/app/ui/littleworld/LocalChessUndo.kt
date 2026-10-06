package com.jiligulu.app.ui.littleworld

/** A CPU round goes back to the last position where the human could choose a different move. */
internal object LocalChessUndo {
    fun gomokuTarget(history: List<GomokuState>, humanPlayer: Int = 1): Int =
        history.indexOfLast { it.currentPlayer == humanPlayer && it.outcome == GomokuOutcome.PLAYING }

    fun xiangqiTarget(history: List<XiangqiState>, cpu: Boolean, humanSide: XiangqiSide = XiangqiSide.RED): Int =
        if (cpu) history.indexOfLast { it.turnSide == humanSide && it.outcome == XiangqiOutcome.PLAYING }
        else history.lastIndex
}
