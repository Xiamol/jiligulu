package com.jiligulu.app.ui.littleworld

internal enum class FinishMood { WIN, LOSE, DRAW, SHARED }
internal data class GameFinishPresentation(
    val headline: String,
    val detail: String,
    val mood: FinishMood,
    val watermark: String? = null,
)

/**
 * 结算字印文案。
 *
 * 需求要求字印只写**确证**的杀法名；拿不到 proof（普通终局）时退回胜负，
 * 不为了好看编一个杀法名。超过两字的会由绘制侧自动缩小。
 */
internal fun xiangqiSealText(proof: XiangqiFinishProof?, outcome: XiangqiOutcome): String = when (proof?.family) {
    XiangqiFinishFamily.CHECKMATE -> "将死"
    XiangqiFinishFamily.DOUBLE_CANNON -> "重炮"
    XiangqiFinishFamily.SMOTHERED_CANNON -> "闷杀"
    XiangqiFinishFamily.STALEMATE -> "困毙"
    XiangqiFinishFamily.GENERAL_CAPTURE -> "擒将"
    else -> if (outcome == XiangqiOutcome.RED_WON) "红方胜" else "黑方胜"
}

internal object GameFinishPresenter {
    fun gomoku(game: GomokuState, humanPlayer: Int?, opponentName: String = "棋友"): GameFinishPresentation? {
        if (game.outcome == GomokuOutcome.PLAYING) return null
        if (game.outcome == GomokuOutcome.DRAW) return GameFinishPresentation("平局", "棋盘坐满，这一局握握手", FinishMood.DRAW)
        val winner = if (game.outcome == GomokuOutcome.HUMAN_WON) 1 else 2
        return if (humanPlayer == null) GameFinishPresentation(if (winner == 1) "黑方胜出" else "白方胜出", "五子相连", FinishMood.SHARED)
        else if (humanPlayer == winner) GameFinishPresentation("你赢啦", "五子相连 · 好棋！", FinishMood.WIN)
        else GameFinishPresentation("这局输了", "${opponentName}五子相连，再下一盘吧", FinishMood.LOSE)
    }

    fun xiangqi(game: XiangqiState, humanSide: XiangqiSide?): GameFinishPresentation? {
        if (game.outcome == XiangqiOutcome.PLAYING) return null
        val winner = if (game.outcome == XiangqiOutcome.RED_WON) XiangqiSide.RED else XiangqiSide.BLACK
        val proof = XiangqiMateClassifier.classify(game)
        val loserName = if (winner == XiangqiSide.RED) "黑方" else "红方"
        val detail = proof?.let { "${it.displayName} · ${it.displayDetail.replace("对方", loserName)}" }
            ?: "${if (winner == XiangqiSide.RED) "红方" else "黑方"}取得这一局"
        val watermark = proof?.let { xiangqiSealText(it, game.outcome) }
        return if (humanSide == null) GameFinishPresentation(if (winner == XiangqiSide.RED) "红方胜出" else "黑方胜出", detail, FinishMood.SHARED, watermark)
        else if (humanSide == winner) GameFinishPresentation("你赢啦", detail, FinishMood.WIN, watermark)
        else GameFinishPresentation("这局输了", detail, FinishMood.LOSE, watermark)
    }

    /** The real completed line, including an overline. No five-in-a-row is invented from outcome text. */
    fun gomokuWinningLine(game: GomokuState): List<GridCell> {
        if (game.outcome == GomokuOutcome.PLAYING || game.outcome == GomokuOutcome.DRAW) return emptyList()
        val move = game.lastMove ?: return emptyList()
        val winner = if (game.outcome == GomokuOutcome.HUMAN_WON) 1 else 2
        if (game.cellAt(move.x, move.y) != winner) return emptyList()
        for (axis in listOf(GridCell(1,0), GridCell(0,1), GridCell(1,1), GridCell(1,-1))) {
            fun run(dx: Int, dy: Int): List<GridCell> = buildList {
                var x=move.x+dx; var y=move.y+dy
                while (x in 0 until game.size && y in 0 until game.size && game.cellAt(x,y)==winner) {
                    add(GridCell(x,y)); x+=dx; y+=dy
                }
            }
            val line = run(-axis.x,-axis.y).reversed() + move + run(axis.x,axis.y)
            if (line.size >= 5) return line
        }
        return emptyList()
    }
}
