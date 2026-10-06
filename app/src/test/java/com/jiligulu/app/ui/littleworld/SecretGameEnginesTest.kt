package com.jiligulu.app.ui.littleworld

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretGameEnginesTest {
    @Test fun snakeFoodAlwaysStartsInsideTheBoardAndOutsideTheBody() {
        repeat(50) { seed ->
            val state = SnakeEngine.newGame(Random(seed))
            val food = requireNotNull(state.food)
            assertTrue(food.x in 0 until state.width)
            assertTrue(food.y in 0 until state.height)
            assertFalse(food in state.body)
            assertEquals(3, state.body.size)
            assertEquals(0, state.score)
        }
        assertEquals(SnakeEngine.newGame(Random(42)), SnakeEngine.newGame(Random(42)))
    }

    @Test fun snakeCannotReverseOrTakeTwoCornersDuringOneTick() {
        val initial = SnakeEngine.newGame(Random(0))
        assertSame(initial, SnakeEngine.turn(initial, SnakeDirection.LEFT))
        val up = SnakeEngine.turn(initial, SnakeDirection.UP)
        assertSame(up, SnakeEngine.turn(up, SnakeDirection.LEFT))
        val movedUp = SnakeEngine.tick(up)
        assertEquals(GridCell(8, 7), movedUp.body.first())
        assertEquals(SnakeDirection.UP, movedUp.direction)
        val movedLeft = SnakeEngine.tick(SnakeEngine.turn(movedUp, SnakeDirection.LEFT))
        assertEquals(GridCell(7, 7), movedLeft.body.first())
    }

    @Test fun snakeMovesIntoTheOldTailWhenItVacates() {
        val initial = SnakeState(
            width = 4,
            height = 4,
            body = listOf(GridCell(1, 1), GridCell(1, 2), GridCell(0, 2), GridCell(0, 1)),
            direction = SnakeDirection.LEFT,
            food = GridCell(3, 3),
        )
        val moved = SnakeEngine.tick(initial)
        assertFalse(moved.gameOver)
        assertEquals(GridCell(0, 1), moved.body.first())
        assertEquals(4, moved.body.size)
        assertEquals(4, moved.body.toSet().size)
        assertEquals(GridCell(1, 1), initial.body.first())
    }

    @Test fun snakeEatingGrowsTheBodyAndPlacesNewFoodOnAFreeSquare() {
        val initial = SnakeState(
            width = 5,
            height = 5,
            body = listOf(GridCell(2, 2), GridCell(1, 2), GridCell(0, 2)),
            food = GridCell(3, 2),
            score = 7,
        )
        val grown = SnakeEngine.tick(initial, Random(6))
        assertEquals(4, grown.body.size)
        assertEquals(GridCell(3, 2), grown.body.first())
        assertEquals(initial.body.last(), grown.body.last())
        assertEquals(8, grown.score)
        assertNotNull(grown.food)
        assertFalse(grown.food in grown.body)
        assertEquals(3, initial.body.size)
        assertEquals(7, initial.score)
    }

    @Test fun snakeWrapsWallsAndOnlyBodyCollisionsEndTheGame() {
        val wall = SnakeState(
            body = listOf(GridCell(15, 8), GridCell(14, 8), GridCell(13, 8)),
        )
        val wallResult = SnakeEngine.tick(wall)
        assertFalse(wallResult.gameOver)
        assertFalse(wallResult.won)
        assertEquals(GridCell(0, 8), wallResult.body.first())
        assertEquals(3, wallResult.body.size)

        val body = SnakeState(
            width = 4,
            height = 4,
            body = listOf(
                GridCell(1, 1), GridCell(1, 2), GridCell(0, 2),
                GridCell(0, 1), GridCell(0, 0), GridCell(1, 0),
            ),
            direction = SnakeDirection.LEFT,
        )
        assertTrue(SnakeEngine.tick(body).gameOver)
    }

    @Test fun snakeWrapsAllFourEdgesAndCanEatAcrossTheBoundary() {
        val cases = listOf(
            SnakeDirection.LEFT to (GridCell(0, 2) to GridCell(4, 2)),
            SnakeDirection.RIGHT to (GridCell(4, 2) to GridCell(0, 2)),
            SnakeDirection.UP to (GridCell(2, 0) to GridCell(2, 4)),
            SnakeDirection.DOWN to (GridCell(2, 4) to GridCell(2, 0)),
        )
        for ((direction, cells) in cases) {
            val original = SnakeState(width = 5, height = 5, body = listOf(cells.first),
                direction = direction, food = cells.second)
            val moved = SnakeEngine.tick(original, Random(6))
            assertEquals(cells.second, moved.body.first())
            assertEquals(2, moved.body.size)
            assertEquals(1, moved.score)
            assertFalse(moved.gameOver)
        }
    }

    @Test fun snakeWrappingOntoItsOwnBodyStillLosesAndItsOldTailCanVacate() {
        val body = listOf(GridCell(0, 1), GridCell(1, 1), GridCell(2, 1), GridCell(3, 1), GridCell(3, 2))
        val state = SnakeState(width = 4, height = 4, body = body, direction = SnakeDirection.LEFT)
        assertTrue(SnakeEngine.tick(state).gameOver)
        val tailAtEdge = state.copy(body = body.dropLast(1))
        assertFalse(SnakeEngine.tick(tailAtEdge).gameOver)
        assertEquals(GridCell(3, 1), SnakeEngine.tick(tailAtEdge).body.first())
    }

    @Test fun snakeFillingTheBoardWinsAndTerminalStatesStayFrozen() {
        val initial = SnakeState(
            width = 2,
            height = 2,
            body = listOf(GridCell(0, 0), GridCell(0, 1), GridCell(1, 1)),
            food = GridCell(1, 0),
        )
        val won = SnakeEngine.tick(initial)
        assertTrue(won.won)
        assertTrue(won.gameOver)
        assertEquals(4, won.body.size)
        assertEquals(1, won.score)
        assertNull(won.food)
        assertSame(won, SnakeEngine.tick(won))
        assertSame(won, SnakeEngine.turn(won, SnakeDirection.UP))
    }

    @Test fun gomokuAlternatesPlayersAndRejectsInvalidMovesWithoutMutation() {
        val initial = GomokuEngine.newGame()
        val human = GomokuEngine.play(initial, 6, 6)
        assertEquals(1, human.cellAt(6, 6))
        assertEquals(2, human.currentPlayer)
        assertEquals(GridCell(6, 6), human.lastMove)
        assertEquals(0, initial.cellAt(6, 6))
        assertSame(human, GomokuEngine.play(human, 6, 6))
        assertSame(human, GomokuEngine.play(human, -1, 6))
        assertSame(human, GomokuEngine.play(human, initial.size, 6))
        assertSame(human, GomokuEngine.play(human, 6, -1))
        assertSame(human, GomokuEngine.play(human, 6, initial.size))
        val cpu = GomokuEngine.play(human, 7, 6)
        assertEquals(2, cpu.cellAt(7, 6))
        assertEquals(1, cpu.currentPlayer)
    }

    @Test fun gomokuFindsHorizontalVerticalAndBothDiagonalWinsForEitherPlayer() {
        val lines = listOf(
            (2..5).map { GridCell(it, 6) } to GridCell(6, 6),
            (2..5).map { GridCell(6, it) } to GridCell(6, 6),
            (2..5).map { GridCell(it, it) } to GridCell(6, 6),
            (2..5).map { GridCell(it, 8 - it) } to GridCell(6, 2),
        )
        for (player in 1..2) for ((stones, move) in lines) {
            val state = position(stones.associateWith { player }, currentPlayer = player)
            val won = GomokuEngine.play(state, move.x, move.y)
            assertEquals(
                if (player == 1) GomokuOutcome.HUMAN_WON else GomokuOutcome.CPU_WON,
                won.outcome,
            )
            assertSame(won, GomokuEngine.play(won, 0, 0))
            assertNull(GomokuEngine.chooseCpuMove(won))
        }
    }

    @Test fun gomokuCanWinByFillingAGapAndByMakingMoreThanFive() {
        val four = listOf(GridCell(2, 6), GridCell(3, 6), GridCell(5, 6), GridCell(6, 6))
        val gap = position(four.associateWith { 1 })
        assertEquals(GomokuOutcome.HUMAN_WON, GomokuEngine.play(gap, 4, 6).outcome)
        val overline = position((four + GridCell(1, 6)).associateWith { 1 })
        assertEquals(GomokuOutcome.HUMAN_WON, GomokuEngine.play(overline, 4, 6).outcome)
    }

    @Test fun gomokuDoesNotTreatRowWrappingOrSeparatedStonesAsAWin() {
        val size = 15
        val wrapped = position(
            listOf(GridCell(size - 2, 4), GridCell(size - 1, 4), GridCell(0, 5), GridCell(1, 5))
                .associateWith { 1 },
            size = size,
        )
        assertEquals(GomokuOutcome.PLAYING, GomokuEngine.play(wrapped, 2, 5).outcome)
        val separated = position(
            listOf(GridCell(0, 5), GridCell(1, 5), GridCell(3, 5), GridCell(4, 5))
                .associateWith { 1 },
        )
        assertEquals(GomokuOutcome.PLAYING, GomokuEngine.play(separated, 5, 5).outcome)
    }

    @Test fun gomokuFillingANonWinningBoardIsADraw() {
        val size = 5
        // Two-wide stripes offset on every row avoid five along every axis.
        val board = List(size * size) { index ->
            val x = index % size
            val y = index / size
            if (x == 4 && y == 4) 0 else (x / 2 + y) % 2 + 1
        }
        val state = GomokuState(size = size, board = board)
        val draw = GomokuEngine.play(state, 4, 4)
        assertEquals(GomokuOutcome.DRAW, draw.outcome)
        assertTrue(draw.board.none { it == 0 })
        assertNull(GomokuEngine.chooseCpuMove(draw))
        assertSame(draw, GomokuEngine.play(draw, 0, 0))
    }

    @Test fun gomokuComputerTakesAWinBeforeBlockingTheHuman() {
        val stones = buildMap {
            for (x in 0..3) {
                put(GridCell(x, 2), 2)
                put(GridCell(x, 10), 1)
            }
        }
        val state = position(stones, currentPlayer = 2)
        val move = requireNotNull(GomokuEngine.chooseCpuMove(state))
        assertEquals(GridCell(4, 2), move)
        assertEquals(GomokuOutcome.CPU_WON, GomokuEngine.play(state, move.x, move.y).outcome)
    }

    @Test fun gomokuComputerBlocksImmediateEdgeAndGapThreats() {
        val edge = position((0..3).associate { GridCell(0, it) to 1 }, currentPlayer = 2)
        assertEquals(GridCell(0, 4), GomokuEngine.chooseCpuMove(edge))
        val gap = position(
            listOf(GridCell(3, 6), GridCell(4, 6), GridCell(6, 6), GridCell(7, 6))
                .associateWith { 1 },
            currentPlayer = 2,
        )
        assertEquals(GridCell(5, 6), GomokuEngine.chooseCpuMove(gap))
    }

    @Test fun gomokuComputerChoosesLegalNearbyMovesAndOnlyMovesOnItsTurn() {
        assertNull(GomokuEngine.chooseCpuMove(GomokuEngine.newGame()))
        val state = GomokuEngine.play(GomokuEngine.newGame(), 6, 6)
        val before = state.board.toList()
        val move = requireNotNull(GomokuEngine.chooseCpuMove(state))
        assertEquals(0, state.cellAt(move.x, move.y))
        assertTrue(abs(move.x - 6) <= 2 && abs(move.y - 6) <= 2)
        assertEquals(before, state.board)
        val next = GomokuEngine.play(state, move.x, move.y)
        assertEquals(2, next.cellAt(move.x, move.y))
        assertEquals(1, next.currentPlayer)
        assertEquals(2, next.board.count { it != 0 })
        assertEquals(GridCell(7, 7), GomokuEngine.chooseCpuMove(GomokuState(currentPlayer = 2)))
    }

    @Test fun gomokuStandardAndLargeBoardsAllowCornerMovesAndDetectFarEdgeWins() {
        assertEquals(15, GomokuEngine.newGame().size)
        for (size in listOf(15, 19)) {
            val initial = GomokuEngine.newGame(size)
            val corner = GomokuEngine.play(initial, size - 1, size - 1)
            assertEquals(size * size, corner.board.size)
            assertEquals(1, corner.cellAt(size - 1, size - 1))
            assertEquals(0, initial.cellAt(size - 1, size - 1))
            assertSame(corner, GomokuEngine.play(corner, size, size - 1))
            assertSame(corner, GomokuEngine.play(corner, size - 1, size))

            val edge = position(
                (size - 5 until size - 1).associate { GridCell(it, size - 1) to 1 },
                size = size,
            )
            assertEquals(
                GomokuOutcome.HUMAN_WON,
                GomokuEngine.play(edge, size - 1, size - 1).outcome,
            )
        }
    }

    @Test fun gomokuComputerBlocksFarEdgeThreatsOnBothBoardSizes() {
        for (size in listOf(15, 19)) {
            val state = position(
                (size - 4 until size).associate { GridCell(size - 1, it) to 1 },
                currentPlayer = 2,
                size = size,
            )
            val expected = GridCell(size - 1, size - 5)
            assertEquals(expected, GomokuEngine.chooseCpuMove(state))
            val blocked = GomokuEngine.play(state, expected.x, expected.y)
            assertEquals(GomokuOutcome.PLAYING, blocked.outcome)
            assertEquals(2, blocked.cellAt(expected.x, expected.y))
            assertEquals(1, blocked.currentPlayer)
        }
    }

    @Test fun gomokuComputerStartsAtCenterAndMakesLegalNearbyRepliesOnBothBoardSizes() {
        for (size in listOf(15, 19)) {
            val center = GridCell(size / 2, size / 2)
            assertEquals(center, GomokuEngine.chooseCpuMove(GomokuState(size = size, currentPlayer = 2)))
            val state = GomokuEngine.play(GomokuEngine.newGame(size), size - 1, 0)
            val move = requireNotNull(GomokuEngine.chooseCpuMove(state))
            assertTrue(move.x in 0 until size && move.y in 0 until size)
            assertEquals(0, state.cellAt(move.x, move.y))
            assertTrue(abs(move.x - (size - 1)) <= 2 && move.y <= 2)
            val next = GomokuEngine.play(state, move.x, move.y)
            assertEquals(2, next.board.count { it != 0 })
            assertEquals(2, next.cellAt(move.x, move.y))
        }
    }

    private fun position(
        stones: Map<GridCell, Int>,
        currentPlayer: Int = 1,
        size: Int = 15,
    ): GomokuState {
        val board = MutableList(size * size) { 0 }
        for ((cell, player) in stones) board[cell.y * size + cell.x] = player
        return GomokuState(size = size, board = board.toList(), currentPlayer = currentPlayer)
    }
}
