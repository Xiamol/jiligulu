package com.jiligulu.app.ui.littleworld

/** Undo a player's own decision, including the opponent's reply when it has already arrived. */
internal object LocalChessUndo {
    fun gomokuTarget(history: List<GomokuState>, humanPlayer: Int = 1): Int =
        history.indexOfLast { it.currentPlayer == humanPlayer && it.outcome == GomokuOutcome.PLAYING }

    fun xiangqiTarget(history: List<XiangqiState>, cpu: Boolean, humanSide: XiangqiSide = XiangqiSide.RED): Int =
        history.indexOfLast { it.turnSide == humanSide && it.outcome == XiangqiOutcome.PLAYING }
}
