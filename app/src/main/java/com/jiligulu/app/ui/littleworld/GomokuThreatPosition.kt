package com.jiligulu.app.ui.littleworld

/** Search-owned mutable board. Only intersecting lines change after make/unmake. */
internal class GomokuThreatPosition(state: GomokuState, private val checkBudget: () -> Unit) {
    val size = state.size
    val cells = state.board.toIntArray()
    val neighbours = IntArray(cells.size)
    var emptyCount = cells.count { it == 0 }
        private set
    var hash = 0L
        private set
    private val patterns = Array(3) { IntArray(cells.size) }
    private val linePatterns = IntArray(19_683) { -1 }
    private val axes = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    private val lines = Array(cells.size) { index ->
        Array(4) { axis ->
            IntArray(9) { offset ->
                val x = index % size + axes[axis].first * (offset - 4)
                val y = index / size + axes[axis].second * (offset - 4)
                if (x in 0 until size && y in 0 until size) y * size + x else -1
            }
        }
    }
    private val affected = Array(cells.size) { index -> lines[index].flatMap { it.asIterable() }.filter { it >= 0 }.distinct().toIntArray() }
    private val nearby = Array(cells.size) { index ->
        val x = index % size
        val y = index / size
        buildList {
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until size && ny in 0 until size) add(ny * size + nx)
            }
        }.toIntArray()
    }
    private val salts = LongArray(cells.size * 2) { index ->
        var value = (index + 1L) * -7046029254386353131L
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value xor (value ushr 31)
    }
    private val saved = Array(64) { IntArray(66) }
    private val moves = IntArray(64)
    private val players = IntArray(64)
    private var level = 0

    init {
        for (index in cells.indices) {
            checkBudget()
            if (cells[index] != 0) {
                hash = hash xor salts[index * 2 + cells[index] - 1]
                for (near in nearby[index]) neighbours[near]++
            }
        }
        for (index in cells.indices) if (cells[index] == 0) {
            checkBudget()
            patterns[1][index] = classify(index, 1)
            patterns[2][index] = classify(index, 2)
        }
    }

    fun cell(index: Int) = GridCell(index % size, index / size)
    fun info(player: Int, index: Int): Int = patterns[player][index]
    fun winningMoves(player: Int): List<Int> = cells.indices.filter { patterns[player][it] and FIVE != 0 }
    fun hasFork(player: Int): Boolean = patterns[player].any { wins(it) >= 2 }

    fun make(index: Int, player: Int) {
        checkBudget()
        check(cells[index] == 0 && level < moves.size)
        moves[level] = index
        players[level] = player
        val changing = affected[index]
        for ((slot, cell) in changing.withIndex()) {
            saved[level][slot * 2] = patterns[1][cell]
            saved[level][slot * 2 + 1] = patterns[2][cell]
        }
        cells[index] = player
        emptyCount--
        hash = hash xor salts[index * 2 + player - 1]
        for (near in nearby[index]) neighbours[near]++
        for (cell in changing) {
            if (cells[cell] == 0) {
                patterns[1][cell] = classify(cell, 1)
                patterns[2][cell] = classify(cell, 2)
            } else {
                patterns[1][cell] = 0
                patterns[2][cell] = 0
            }
        }
        level++
    }

    fun unmake() {
        level--
        val index = moves[level]
        cells[index] = 0
        emptyCount++
        hash = hash xor salts[index * 2 + players[level] - 1]
        for (near in nearby[index]) neighbours[near]--
        for ((slot, cell) in affected[index].withIndex()) {
            patterns[1][cell] = saved[level][slot * 2]
            patterns[2][cell] = saved[level][slot * 2 + 1]
        }
    }

    private fun classify(index: Int, player: Int): Int {
        var winningPoints = 0
        var liveThrees = 0
        var small = 0
        for (line in lines[index]) {
            var code = 0
            for (i in line.indices) {
                val cell = line[i]
                val value = if (i == 4) 1 else when {
                    cell < 0 -> 2 // Edges block a line just like an opposing stone.
                    cells[cell] == 0 -> 0
                    cells[cell] == player -> 1
                    else -> 2
                }
                code = code * 3 + value
            }
            var pattern = linePatterns[code]
            if (pattern < 0) { pattern = classifyLine(code); linePatterns[code] = pattern }
            if (pattern and FIVE != 0) return FIVE
            winningPoints += Integer.bitCount(pattern and 511)
            if (pattern and 512 != 0) liveThrees++
            small += pattern ushr 10
        }
        return (winningPoints shl 20) or (liveThrees shl 16) or small
    }

    /** Distinct five completions, then genuine open-four extensions of a three. */
    private fun classifyLine(code: Int): Int {
        var remaining = code
        val line = IntArray(9)
        for (i in 8 downTo 0) { line[i] = remaining % 3; remaining /= 3 }
        var winning = 0
        var small = 0
        for (start in 0..4) {
            var mine = 0
            var empty = -1
            var blocked = false
            for (i in start until start + 5) when (line[i]) {
                1 -> mine++
                0 -> empty = i
                else -> { blocked = true; break }
            }
            if (blocked) continue
            if (mine == 5) return FIVE
            if (mine == 4) winning = winning or (1 shl empty)
            small = maxOf(small, when (mine) { 3 -> 360; 2 -> 70; 1 -> 6; else -> 0 })
        }
        var liveThree = false
        for (start in 1..4) {
            if (line[start - 1] != 0 || line[start + 4] != 0) continue
            var mine = 0
            var empty = 0
            for (i in start until start + 4) when (line[i]) { 1 -> mine++; 0 -> empty++ }
            if (mine == 3 && empty == 1) liveThree = true
            if (mine == 2 && empty == 2) small = maxOf(small, 240)
        }
        return winning or (if (liveThree) 512 else 0) or (small shl 10)
    }

    companion object {
        private const val FIVE = 1 shl 24
        fun wins(info: Int): Int = (info ushr 20) and 15
        fun threes(info: Int): Int = (info ushr 16) and 7
        fun score(info: Int): Int {
            if (info and FIVE != 0) return 5_000_000
            val fours = wins(info)
            val threes = threes(info)
            val small = info and 65_535
            return small + when {
                fours >= 2 -> 800_000
                fours == 1 && threes > 0 -> 260_000
                fours == 1 -> 30_000
                threes >= 2 -> 110_000
                threes == 1 -> 8_500
                else -> 0
            }
        }
    }
}
