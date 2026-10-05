package com.jiligulu.app.ui.littleworld

internal data class XiangqiPuzzle(val title:String,val position:XiangqiState,val solution:XiangqiMove)

internal object XiangqiPuzzles {
    private fun board(vararg pieces:Pair<GridCell,Int>)=XiangqiState(board=MutableList(90){0}.apply {
        this[9*9+4]=XiangqiEngine.GENERAL;this[4]=-XiangqiEngine.GENERAL
        pieces.forEach {(cell,piece)->this[cell.y*9+cell.x]=piece}
    })
    val all=listOf(
        XiangqiPuzzle("车的星轨",board(GridCell(3,0) to -2,GridCell(5,0) to -2,
            GridCell(4,5) to 7,GridCell(3,2) to 5,GridCell(2,2) to 4),XiangqiMove(GridCell(3,2),GridCell(3,0))),
        XiangqiPuzzle("炮的小烟花",board(GridCell(3,0) to -2,GridCell(5,0) to -2,
            GridCell(4,1) to -7,GridCell(2,3) to 6),XiangqiMove(GridCell(2,3),GridCell(4,3))),
        XiangqiPuzzle("马的跃光",board(GridCell(3,0) to -2,GridCell(5,0) to -2,
            GridCell(4,1) to -7,GridCell(0,0) to 4),XiangqiMove(GridCell(0,0),GridCell(2,1)))
    )
}
