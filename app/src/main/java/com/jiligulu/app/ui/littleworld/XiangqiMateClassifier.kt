package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

enum class XiangqiFinishFamily {
    CHECKMATE, STALEMATE, GENERAL_CAPTURE, DOUBLE_CANNON, SMOTHERED_CANNON,
}

data class XiangqiFinishProof(
    val winner: XiangqiSide,
    val family: XiangqiFinishFamily,
    val displayName: String,
    val displayDetail: String,
    val checkingCells: List<GridCell> = emptyList(),
)

/** Only describe what the final board proves. The last moved piece alone does not prove a named mate. */
object XiangqiMateClassifier {
    fun classify(finalState: XiangqiState): XiangqiFinishProof? {
        val winner = when (finalState.outcome) {
            XiangqiOutcome.PLAYING, XiangqiOutcome.DRAW -> return null
            XiangqiOutcome.RED_WON -> XiangqiSide.RED
            XiangqiOutcome.BLACK_WON -> XiangqiSide.BLACK
        }
        val loser = winner.opponent
        if (finalState.board.count { it == winner.sign * XiangqiEngine.GENERAL } != 1) return null
        if (XiangqiEngine.isInCheck(finalState, winner)) return null
        val generalIndex = finalState.board.indexOf(loser.sign * XiangqiEngine.GENERAL)
        if (generalIndex < 0) {
            return XiangqiFinishProof(winner, XiangqiFinishFamily.GENERAL_CAPTURE,
                "擒将", "对方将帅已被吃掉，本局结束。")
        }
        if (finalState.board.count { it == loser.sign * XiangqiEngine.GENERAL } != 1) return null
        val position = finalState.copy(turnSide = loser, outcome = XiangqiOutcome.PLAYING)
        if (XiangqiEngine.legalMoves(position).isNotEmpty()) return null
        val checking = XiangqiEngine.checkingPieces(position, loser)
        if (checking.isEmpty()) {
            return XiangqiFinishProof(winner, XiangqiFinishFamily.STALEMATE,
                "困毙", "对方虽未被将军，但已无合法着法；象棋中判负。")
        }

        val general = GridCell(generalIndex % 9, generalIndex / 9)
        val soleCannon = checking.singleOrNull()?.takeIf {
            abs(position.pieceAt(it.x, it.y)) == XiangqiEngine.CANNON
        }
        if (soleCannon != null) {
            val between = interveningCells(position, soleCannon, general)
            if (between.size == 1 && position.pieceAt(between[0].x, between[0].y) == winner.sign * XiangqiEngine.CANNON) {
                return XiangqiFinishProof(winner, XiangqiFinishFamily.DOUBLE_CANNON,
                    "重炮将死", "两炮与将帅共线，一炮作炮架，另一炮将军，对方无合法应手。", checking)
            }
            val backRank = if (loser == XiangqiSide.BLACK) 0 else 9
            val forward = if (loser == XiangqiSide.BLACK) 1 else 8
            val ownBlocked = general == GridCell(4, backRank) &&
                listOf(GridCell(3, backRank), GridCell(5, backRank), GridCell(4, forward))
                    .all { position.pieceAt(it.x, it.y) * loser.sign > 0 }
            if (ownBlocked) {
                return XiangqiFinishProof(winner, XiangqiFinishFamily.SMOTHERED_CANNON,
                    "闷杀", "将帅被己方棋子堵在宫底，遭炮将军且无合法应手。", checking)
            }
        }
        return XiangqiFinishProof(winner, XiangqiFinishFamily.CHECKMATE,
            "将死", "对方正在被将军，所有合法应将着法均已耗尽。", checking)
    }

    private fun interveningCells(state: XiangqiState, from: GridCell, to: GridCell): List<GridCell> {
        if (from.x != to.x && from.y != to.y) return emptyList()
        val dx = (to.x - from.x).coerceIn(-1, 1)
        val dy = (to.y - from.y).coerceIn(-1, 1)
        val occupied = mutableListOf<GridCell>()
        var cell = GridCell(from.x + dx, from.y + dy)
        while (cell != to) {
            if (state.pieceAt(cell.x, cell.y) != 0) occupied += cell
            cell = GridCell(cell.x + dx, cell.y + dy)
        }
        return occupied
    }
}
