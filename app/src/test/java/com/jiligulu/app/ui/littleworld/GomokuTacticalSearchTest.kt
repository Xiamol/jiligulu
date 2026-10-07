package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuTacticalSearchTest {
    @Test fun aSingleStoneHasANonemptyLegalFrontierAtTheCenterAndCornerOfBothBoardSizes() {
        for (size in listOf(15, 19)) {
            for ((stone, expectedEmptyNeighbours) in listOf(GridCell(size / 2, size / 2) to 24, GridCell(size - 1, 0) to 8)) {
                val state = GomokuEngine.play(GomokuEngine.newGame(size), stone.x, stone.y)
                val position = GomokuThreatPosition(state) {}
                assertEquals(expectedEmptyNeighbours, position.cells.indices.count {
                    position.cells[it] == 0 && position.neighbours[it] > 0
                })
                val before = state.board.toList()
                val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 100))
                assertEquals(0, state.cellAt(move.x, move.y))
                assertTrue(kotlin.math.abs(move.x - stone.x) <= 2 && kotlin.math.abs(move.y - stone.y) <= 2)
                assertEquals(before, state.board)
            }
        }
    }

    @Test fun takesItsOwnFiveEvenWhenTheOpponentHasTwoSeparateWinningPoints() {
        val state = board(1, listOf(3 to 3, 4 to 3, 6 to 3, 7 to 3),
            listOf(2 to 10, 3 to 10, 4 to 10, 5 to 10))
        assertEquals(setOf(GridCell(1, 10), GridCell(6, 10)), winningPoints(state, 2))
        assertEquals(GridCell(5, 3), GomokuStrongMoveHelper.chooseMove(state, 1_200))
    }

    @Test fun blocksTheOnlyClosedFourDespiteHavingItsOwnLiveThree() {
        val state = board(1, listOf(2 to 4, 6 to 8, 7 to 8, 8 to 8),
            listOf(3 to 4, 4 to 4, 5 to 4, 6 to 4))
        assertEquals(GridCell(7, 4), GomokuStrongMoveHelper.chooseMove(state, 1_200))
    }

    @Test fun blocksTheOnlyBrokenFourAcrossTheFarBoardEdge() {
        val state = board(1, listOf(0 to 1, 6 to 8, 7 to 8, 8 to 8, 4 to 5, 10 to 9, 6 to 6),
            listOf(0 to 2, 0 to 3, 0 to 5, 0 to 6, 10 to 12, 11 to 12, 12 to 12))
        assertEquals(GridCell(0, 4), GomokuStrongMoveHelper.chooseMove(state, 1_200))
    }

    @Test fun openFourReallyWinsAgainstEveryLegalReply() {
        val state = board(1, listOf(5 to 7, 6 to 7, 7 to 7), listOf(4 to 4, 8 to 4, 12 to 12))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_200))
        assertTrue(move in setOf(GridCell(4, 7), GridCell(8, 7)))
        val after = GomokuEngine.play(state, move.x, move.y)
        for (reply in emptyCells(after)) {
            val defended = GomokuEngine.play(after, reply.x, reply.y)
            assertTrue("$reply cannot block both ends", winningPoints(defended, 1).isNotEmpty())
        }
    }

    @Test fun findsTheFourThreeCombinationAndItsForcedContinuation() {
        val state = board(1, listOf(4 to 7, 5 to 7, 6 to 7, 7 to 6, 7 to 8),
            listOf(3 to 7, 0 to 0, 2 to 1, 14 to 0, 14 to 14))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_200))
        assertEquals(GridCell(7, 7), move)
        val after = GomokuEngine.play(state, move.x, move.y)
        assertEquals(setOf(GridCell(8, 7)), survivingReplies(after, 1))
        val blocked = GomokuEngine.play(after, 8, 7)
        val continuation = requireNotNull(GomokuStrongMoveHelper.chooseMove(blocked, 1_200))
        assertTrue(continuation in setOf(GridCell(7, 5), GridCell(7, 9)))
        assertEquals(emptySet<GridCell>(), survivingReplies(GomokuEngine.play(blocked, continuation.x, continuation.y), 1))
    }

    @Test fun seesAThreeAttackContinuousFourLineBeyondTheOldForcedExtension() {
        val state = longFourLine()
        val original = state.board.toList()
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_500))
        assertTrue("$move must initiate the proven attack", move in setOf(GridCell(7, 7), GridCell(7, 10)))
        assertTrue(hasVcf(state, 1, 3))
        // A fixed independently established line; the oracle checks every defender move.
        var current = GomokuEngine.play(state, 7, 7)
        assertEquals(setOf(GridCell(8, 7)), survivingReplies(current, 1))
        current = GomokuEngine.play(current, 8, 7)
        current = GomokuEngine.play(current, 7, 10)
        assertEquals(setOf(GridCell(7, 11)), survivingReplies(current, 1))
        current = GomokuEngine.play(current, 7, 11)
        current = GomokuEngine.play(current, 8, 9)
        assertEquals(setOf(GridCell(6, 11), GridCell(11, 6)), winningPoints(current, 1))
        assertTrue(survivingReplies(current, 1).isEmpty())
        assertEquals(original, state.board)
        assertEquals(1, state.currentPlayer)
        assertEquals(GomokuOutcome.PLAYING, state.outcome)
    }

    @Test fun blocksTheOpponentsContinuousFoursBeforeThereIsAnImmediateFive() {
        val state = longFourLine().copy(currentPlayer = 2, board = longFourLine().board.toMutableList().apply { this[14 * 15 + 14] = 0 })
        assertTrue(winningPoints(state, 1).isEmpty())
        assertTrue(hasVcf(state, 1, 3))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_500))
        assertTrue(move in setOf(GridCell(7, 7), GridCell(8, 7), GridCell(8, 9), GridCell(7, 10), GridCell(7, 11)))
        val after = GomokuEngine.play(state, move.x, move.y)
        assertFalse("$move walks into a longer forcing attack", hasVcf(after, 1, 4))
    }

    @Test fun aFalseEdgeDoubleThreeDoesNotDistractFromTheRealOpenFourThreat() {
        val state = board(1, listOf(1 to 1, 2 to 1, 3 to 2, 3 to 3, 14 to 14),
            listOf(5 to 1, 3 to 5, 8 to 10, 9 to 10, 10 to 10))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_200))
        assertTrue("$move must defend the real three", move in setOf(GridCell(7, 10), GridCell(11, 10)))
        val after = GomokuEngine.play(state, move.x, move.y)
        assertFalse(hasVcf(after, 2, 1))
    }

    @Test fun aRealDoubleThreeMustYieldToTheOpponentsDistantCounterFours() {
        val state = board(1, listOf(2 to 3, 4 to 3, 3 to 2, 3 to 4, 3 to 9, 7 to 8, 14 to 14),
            listOf(4 to 9, 5 to 9, 6 to 9, 7 to 10, 7 to 11, 9 to 10, 10 to 9))
        assertTrue(winningPoints(state, 1).isEmpty())
        assertTrue(winningPoints(state, 2).isEmpty())
        val doubleThree = GomokuEngine.play(state, 3, 3)
        // Both threes are genuine: each has a continuation to a two-ended four.
        assertEquals(2, winningPoints(GomokuEngine.play(doubleThree.copy(currentPlayer = 1), 1, 3), 1).size)
        assertEquals(2, winningPoints(GomokuEngine.play(doubleThree.copy(currentPlayer = 1), 3, 1), 1).size)
        assertTrue(hasVcf(doubleThree, 2, 3))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_500))
        assertFalse("$move ignores the distant forcing counterattack", hasVcf(GomokuEngine.play(state, move.x, move.y), 2, 4))
    }

    @Test fun twoDistantWinningPointsAreRecognizedAsImpossibleToCoverWithOneStone() {
        val state = board(1, listOf(0 to 2, 14 to 12, 6 to 6, 8 to 7, 6 to 9, 8 to 10, 4 to 4, 10 to 3),
            listOf(1 to 2, 3 to 2, 4 to 2, 5 to 2, 9 to 12, 10 to 12, 11 to 12, 13 to 12))
        assertEquals(setOf(GridCell(2, 2), GridCell(12, 12)), winningPoints(state, 2))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, 1_200))
        assertEquals(0, state.cellAt(move.x, move.y))
        assertTrue(winningPoints(GomokuEngine.play(state, move.x, move.y), 2).isNotEmpty())
    }

    @Test fun incrementalThreatsMatchActualFiveWindowsAndUndoRestoresThem() {
        val state = longFourLine()
        val position = GomokuThreatPosition(state) {}
        val originalHash = position.hash
        position.make(7 * 15 + 7, 1)
        position.make(7 * 15 + 8, 2)
        position.make(10 * 15 + 7, 1)
        val actual = state.copy(board = position.cells.toList())
        for (side in 1..2) assertEquals(winningPoints(actual, side), position.winningMoves(side).map(position::cell).toSet())
        repeat(3) { position.unmake() }
        assertEquals(originalHash, position.hash)
        assertEquals(state.board, position.cells.toList())
        for (side in 1..2) assertEquals(winningPoints(state, side), position.winningMoves(side).map(position::cell).toSet())
    }

    @Test fun cancellationDuringConstructionOrSearchDiscardsTheResultAndPreservesTheBoard() {
        val state = board(1, listOf(7 to 7, 6 to 8, 10 to 5), listOf(8 to 7, 9 to 8, 5 to 5))
        val before = state.board.toList()
        assertNull(GomokuStrongMoveHelper.chooseMove(state, 5_000) { true })
        var earlyChecks = 0
        assertNull(GomokuStrongMoveHelper.chooseMove(state, 5_000) { ++earlyChecks >= 3 })
        var laterChecks = 0
        assertNull(GomokuStrongMoveHelper.chooseMove(state, 5_000) { ++laterChecks >= 40 })
        assertTrue(laterChecks >= 40)
        assertEquals(before, state.board)
    }

    @Test fun interruptionIsHonoredAndBudgetExhaustionStillReturnsALegalMove() {
        val state = board(1, listOf(7 to 7, 6 to 8, 10 to 5), listOf(8 to 7, 9 to 8, 5 to 5))
        try {
            Thread.currentThread().interrupt()
            assertNull(GomokuStrongMoveHelper.chooseMove(state, 1_200))
        } finally { Thread.interrupted() }
        val move = GomokuStrongMoveHelper.chooseMove(state, 100)
        assertNotNull(move)
        assertEquals(0, state.cellAt(requireNotNull(move).x, move.y))
    }

    private fun longFourLine() = board(1,
        listOf(4 to 7, 5 to 7, 6 to 7, 7 to 8, 7 to 9, 9 to 8, 10 to 7),
        listOf(3 to 7, 7 to 6, 0 to 0, 2 to 1, 0 to 4, 14 to 0, 14 to 14))

    private fun board(player: Int, black: List<Pair<Int, Int>>, white: List<Pair<Int, Int>>): GomokuState =
        GomokuState(currentPlayer = player, board = MutableList(225) { 0 }.apply {
            black.forEach { (x, y) -> this[y * 15 + x] = 1 }
            white.forEach { (x, y) -> this[y * 15 + x] = 2 }
        })

    private fun emptyCells(state: GomokuState): List<GridCell> = state.board.indices
        .filter { state.board[it] == 0 }.map { GridCell(it % state.size, it / state.size) }

    /** Independent rule oracle: enumerate five-cell windows, without production pattern scores. */
    private fun winningPoints(state: GomokuState, side: Int): Set<GridCell> = buildSet {
        for (y in 0 until state.size) for (x in 0 until state.size) {
            for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                val endX = x + dx * 4
                val endY = y + dy * 4
                if (endX !in 0 until state.size || endY !in 0 until state.size) continue
                var mine = 0
                var empty = 0
                var point = GridCell(0, 0)
                for (step in 0..4) {
                    val cell = GridCell(x + dx * step, y + dy * step)
                    when (state.cellAt(cell.x, cell.y)) {
                        side -> mine++
                        0 -> { empty++; point = cell }
                    }
                }
                if (mine == 4 && empty == 1) add(point)
            }
        }
    }

    private fun survivingReplies(afterAttack: GomokuState, attacker: Int): Set<GridCell> = emptyCells(afterAttack).filter { reply ->
        val defended = GomokuEngine.play(afterAttack, reply.x, reply.y)
        winningPoints(defended, attacker).isEmpty()
    }.toSet()

    /** Exhaustive forcing-four oracle, separate from the engine's cached patterns/ordering. */
    private fun hasVcf(state: GomokuState, attacker: Int, attacksLeft: Int): Boolean {
        if (winningPoints(state, attacker).isNotEmpty()) return true
        if (attacksLeft == 0) return false
        val counterWins = winningPoints(state, 3 - attacker)
        if (counterWins.size > 1) return false
        val choices = if (counterWins.size == 1) counterWins.toList() else emptyCells(state)
        for (attack in choices) {
            val next = GomokuEngine.play(state.copy(currentPlayer = attacker), attack.x, attack.y)
            if (winningPoints(next, 3 - attacker).isNotEmpty()) continue
            val direct = winningPoints(next, attacker)
            if (direct.size >= 2) return true
            if (direct.size == 1) {
                val block = direct.first()
                val defended = GomokuEngine.play(next, block.x, block.y)
                if (hasVcf(defended, attacker, attacksLeft - 1)) return true
            }
        }
        return false
    }
}
