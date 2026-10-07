package com.jiligulu.app.ui.littleworld

/** A peer may send the same board, one legal move, or an explicit fresh-board revision. */
internal object XiangqiSnapshotRules {
    fun acceptsDraw(round:Int,revision:Int,game:XiangqiState,offer:RoomDrawOffer,consented:Boolean,result:RoomControl.DrawResult):Boolean =
        game.outcome==XiangqiOutcome.PLAYING&&RoomDrawRules.acceptsResult(round,revision,offer,consented,result)
    fun accepts(revision:Int,game:XiangqiState,initialized:Boolean,next:XiangqiLanMessage.Snapshot,allowRestart:Boolean=true):Boolean {
        if(!initialized) return next.revision==0 && next.game==XiangqiEngine.newGame()
        if(next.revision==revision) return next.game==game
        if(revision==Int.MAX_VALUE||next.revision!=revision+1) return false
        return allowRestart && next.game==XiangqiEngine.newGame() || next.game.lastMove?.let {XiangqiEngine.play(game,it)==next.game}==true
    }

    /** Undo is its own consent-bound transition; it does not broaden normal STATE messages. */
    fun acceptsUndo(
        revision: Int,
        game: XiangqiState,
        initialized: Boolean,
        localSide: XiangqiSide,
        history: XiangqiUndoHistory,
        next: XiangqiLanMessage.UndoSnapshot,
    ): Boolean = initialized && history.acceptsGuestUndo(localSide, revision, game, next)
}
