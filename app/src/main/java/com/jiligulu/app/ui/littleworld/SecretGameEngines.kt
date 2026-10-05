package com.jiligulu.app.ui.littleworld

import kotlin.math.abs
import kotlin.random.Random

data class GridCell(val x: Int, val y: Int)

enum class SnakeDirection(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    fun isOpposite(other: SnakeDirection): Boolean = dx == -other.dx && dy == -other.dy
}

data class SnakeState(
    val width: Int = 16,
    val height: Int = 16,
    /** The head is first, and the tail is last. */
    val body: List<GridCell> = listOf(GridCell(8, 8), GridCell(7, 8), GridCell(6, 8)),
    val direction: SnakeDirection = SnakeDirection.RIGHT,
    val pendingDirection: SnakeDirection = direction,
    val food: GridCell? = null,
    val score: Int = 0,
    val gameOver: Boolean = false,
    val won: Boolean = false,
) {
    init {
        require(width > 0 && height > 0)
        require(body.isNotEmpty())
    }
}

/** Offline rules with no timers or UI dependencies; the screen decides when to tick. */
object SnakeEngine {
    fun newGame(random: Random = Random.Default): SnakeState {
        val state = SnakeState()
        return state.copy(food = randomFood(state.width, state.height, state.body, random))
    }

    fun turn(state: SnakeState, direction: SnakeDirection): SnakeState {
        // Accept one corner per tick so rapid taps cannot reverse into the neck.
        if (state.gameOver || state.pendingDirection != state.direction ||
            direction == state.direction || direction.isOpposite(state.direction)
        ) return state
        return state.copy(pendingDirection = direction)
    }

    fun tick(state: SnakeState, random: Random = Random.Default): SnakeState {
        if (state.gameOver) return state
        val direction = state.pendingDirection.takeUnless { it.isOpposite(state.direction) }
            ?: state.direction
        val head = state.body.first()
        val nextHead = GridCell(head.x + direction.dx, head.y + direction.dy)
        val grows = nextHead == state.food
        // On a normal move the tail leaves before the head enters its old square.
        val occupied = if (grows) state.body else state.body.dropLast(1)
        if (nextHead.x !in 0 until state.width || nextHead.y !in 0 until state.height ||
            nextHead in occupied
        ) return state.copy(direction = direction, pendingDirection = direction, gameOver = true)

        val body = listOf(nextHead) + if (grows) state.body else state.body.dropLast(1)
        val won = body.size == state.width * state.height
        return state.copy(
            body = body,
            direction = direction,
            pendingDirection = direction,
            food = if (won) null else if (grows) {
                randomFood(state.width, state.height, body, random)
            } else state.food,
            score = state.score + if (grows) 1 else 0,
            gameOver = won,
            won = won,
        )
    }

    private fun randomFood(width: Int, height: Int, body: List<GridCell>, random: Random): GridCell? {
        val occupied = body.toHashSet()
        val free = buildList {
            for (y in 0 until height) for (x in 0 until width) {
                val cell = GridCell(x, y)
                if (cell !in occupied) add(cell)
            }
        }
        return if (free.isEmpty()) null else free[random.nextInt(free.size)]
    }
}

enum class GomokuOutcome { PLAYING, HUMAN_WON, CPU_WON, DRAW }

data class GomokuState(
    val size: Int = 15,
    /** Row-major board: 0 is empty, 1 is the human, 2 is the computer. */
    val board: List<Int> = List(size * size) { 0 },
    val currentPlayer: Int = 1,
    val outcome: GomokuOutcome = GomokuOutcome.PLAYING,
    val lastMove: GridCell? = null,
) {
    init {
        require(size >= 5)
        require(board.size == size * size)
        require(currentPlayer == 1 || currentPlayer == 2)
    }

    fun cellAt(x: Int, y: Int): Int = board[y * size + x]
}

/** Freestyle Gomoku: five or more in a line wins, with the human playing first. */
object GomokuEngine {
    private val axes = listOf(GridCell(1, 0), GridCell(0, 1), GridCell(1, 1), GridCell(1, -1))

    fun newGame(size: Int = 15): GomokuState = GomokuState(size = size)

    fun play(state: GomokuState, x: Int, y: Int): GomokuState {
        if (state.outcome != GomokuOutcome.PLAYING || !inside(state, x, y) ||
            state.cellAt(x, y) != 0
        ) return state

        val player = state.currentPlayer
        val board = state.board.toMutableList().apply { this[y * state.size + x] = player }.toList()
        val outcome = when {
            wouldWin(state, GridCell(x, y), player) -> if (player == 1) {
                GomokuOutcome.HUMAN_WON
            } else GomokuOutcome.CPU_WON
            board.none { it == 0 } -> GomokuOutcome.DRAW
            else -> GomokuOutcome.PLAYING
        }
        return state.copy(
            board = board,
            currentPlayer = if (outcome == GomokuOutcome.PLAYING) 3 - player else player,
            outcome = outcome,
            lastMove = GridCell(x, y),
        )
    }

    fun chooseCpuMove(state: GomokuState): GridCell? {
        if (state.outcome != GomokuOutcome.PLAYING || state.currentPlayer != 2) return null
        val empty = buildList {
            for (y in 0 until state.size) for (x in 0 until state.size) {
                if (state.cellAt(x, y) == 0) add(GridCell(x, y))
            }
        }
        if (empty.isEmpty()) return null

        // Check the whole board for forced moves before applying the nearby heuristic.
        val winning = empty.filter { wouldWin(state, it, 2) }
        if (winning.isNotEmpty()) return bestCandidate(state, winning)
        val blocking = empty.filter { wouldWin(state, it, 1) }
        if (blocking.isNotEmpty()) return bestCandidate(state, blocking)

        val occupied = buildList {
            for (y in 0 until state.size) for (x in 0 until state.size) {
                if (state.cellAt(x, y) != 0) add(GridCell(x, y))
            }
        }
        val nearby = empty.filter { cell ->
            occupied.any { abs(it.x - cell.x) <= 2 && abs(it.y - cell.y) <= 2 }
        }
        return bestCandidate(state, nearby.ifEmpty { empty })
    }

    private fun bestCandidate(state: GomokuState, candidates: List<GridCell>): GridCell? =
        candidates.maxWithOrNull(
            compareBy<GridCell> { lineScore(state, it, 2) * 11 / 10 + lineScore(state, it, 1) }
                .thenBy { -(abs(it.x - state.size / 2) + abs(it.y - state.size / 2)) }
                .thenBy { -(it.y * state.size + it.x) },
        )

    private fun wouldWin(state: GomokuState, move: GridCell, player: Int): Boolean =
        axes.any { axis ->
            1 + runLength(state, move, axis, player) +
                runLength(state, move, GridCell(-axis.x, -axis.y), player) >= 5
        }

    private fun lineScore(state: GomokuState, move: GridCell, player: Int): Int = axes.fold(0) { total, axis ->
        val forwards = runLength(state, move, axis, player)
        val backwards = runLength(state, move, GridCell(-axis.x, -axis.y), player)
        val length = 1 + forwards + backwards
        val frontX = move.x + axis.x * (forwards + 1)
        val frontY = move.y + axis.y * (forwards + 1)
        val backX = move.x - axis.x * (backwards + 1)
        val backY = move.y - axis.y * (backwards + 1)
        val openEnds = (if (inside(state, frontX, frontY) && state.cellAt(frontX, frontY) == 0) 1 else 0) +
            (if (inside(state, backX, backY) && state.cellAt(backX, backY) == 0) 1 else 0)
        total + when {
            length >= 5 -> 1_000_000
            openEnds == 0 -> 0
            length == 4 -> if (openEnds == 2) 100_000 else 12_000
            length == 3 -> if (openEnds == 2) 4_000 else 300
            length == 2 -> if (openEnds == 2) 160 else 30
            else -> if (openEnds == 2) 8 else 1
        }
    }

    private fun runLength(state: GomokuState, move: GridCell, step: GridCell, player: Int): Int {
        var length = 0
        var x = move.x + step.x
        var y = move.y + step.y
        while (inside(state, x, y) && state.cellAt(x, y) == player) {
            length++
            x += step.x
            y += step.y
        }
        return length
    }

    private fun inside(state: GomokuState, x: Int, y: Int): Boolean =
        x in 0 until state.size && y in 0 until state.size
}
