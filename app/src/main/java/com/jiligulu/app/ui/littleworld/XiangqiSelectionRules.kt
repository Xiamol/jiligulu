package com.jiligulu.app.ui.littleworld

/** Selection is a visual hint. It can never advance or replace the authoritative board. */
internal object XiangqiSelectionRules {
    fun accepts(
        revision: Int,
        game: XiangqiState,
        localSide: XiangqiSide?,
        message: XiangqiLanMessage.Select,
    ): Boolean = localSide != null && message.revision == revision &&
        canSelect(game, localSide.opponent, message.cell)

    fun canSelect(game: XiangqiState, side: XiangqiSide, cell: GridCell?): Boolean {
        // A current-revision clear remains valid after a turn changes or the game ends.
        if (cell == null) return true
        return cell.x in 0..8 && cell.y in 0..9 &&
            game.outcome == XiangqiOutcome.PLAYING && game.turnSide == side &&
            game.pieceAt(cell.x, cell.y) * side.sign > 0
    }
}

/** One latest-value slot, flushed at most every 100 ms by the owning transport. */
internal class XiangqiSelectionHints {
    private var pending: XiangqiLanMessage.Select? = null
    private var lastSent: XiangqiLanMessage.Select? = null

    fun offer(message: XiangqiLanMessage.Select): Boolean {
        if (message == pending) return false
        if (message == lastSent || lastSent == null && message.cell == null) {
            pending = null
            return false
        }
        pending = message
        return true
    }

    fun take(): XiangqiLanMessage.Select? = pending.also {
        pending = null
        if (it != null) lastSent = it
    }

    fun clear() { pending = null; lastSent = null }
}
