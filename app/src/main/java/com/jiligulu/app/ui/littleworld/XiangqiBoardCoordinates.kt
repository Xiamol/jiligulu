package com.jiligulu.app.ui.littleworld

/** Perspective changes squares only. Glyphs always remain upright in Canvas. */
internal object XiangqiBoardCoordinates {
    fun display(cell: GridCell, flipped: Boolean): GridCell {
        require(cell.x in 0..8 && cell.y in 0..9)
        return if(flipped) GridCell(8-cell.x,9-cell.y) else cell
    }
    fun model(cell: GridCell, flipped: Boolean): GridCell = display(cell,flipped)
}
