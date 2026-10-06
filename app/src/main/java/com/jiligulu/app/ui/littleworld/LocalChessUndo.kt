package com.jiligulu.app.ui.littleworld

/** A CPU round goes back to the last position where the human could choose a different move. */
internal object LocalChessUndo {
    fun gomokuTarget(history: List<GomokuState>): Int =
        history.indexOfLast { it.currentPlayer == 1 && it.outcome == GomokuOutcome.PLAYING }

    fun xiangqiTarget(history: List<XiangqiState>, cpu: Boolean): Int =
        if (cpu) history.indexOfLast { it.turnSide == XiangqiSide.RED && it.outcome == XiangqiOutcome.PLAYING }
        else history.lastIndex
}
