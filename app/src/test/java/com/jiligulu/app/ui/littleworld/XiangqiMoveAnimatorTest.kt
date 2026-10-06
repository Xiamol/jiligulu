package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiMoveAnimatorTest {
    private val key = XiangqiPresentationKey(epoch = 3, round = 1)
    private fun snapshot(state: XiangqiState, owner: XiangqiPresentationKey = key) = XiangqiViewSnapshot.of(state, owner)

    @Test fun legalCaptureRetainsTheActualVictimFromBeforeTheMove() {
        val before = sparse(GridCell(0, 5) to 5, GridCell(0, 3) to -4)
        val move = XiangqiMove(GridCell(0, 5), GridCell(0, 3))
        val after = XiangqiEngine.play(before, move)
        val step = requireNotNull(XiangqiMoveAnimator.stepFrom(snapshot(before), snapshot(after)))
        assertEquals(5, step.mover)
        assertEquals(-4, step.captured)
        assertEquals(move.from, step.from)
        assertEquals(move.to, step.to)
        assertEquals(before, step.before)
        assertEquals(after, step.after)
        assertEquals(-4, before.pieceAt(0, 3))
        assertEquals(5, after.pieceAt(0, 3))
    }

    @Test fun undoDoesNotAnimateARedRookUsingTheOldBlackRookMove() {
        val initial = sparse(GridCell(0, 6) to 5, GridCell(0, 3) to -5)
        val first = XiangqiEngine.play(initial, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        val second = XiangqiEngine.play(first, XiangqiMove(GridCell(0, 3), GridCell(1, 3)))
        val third = XiangqiEngine.play(second, XiangqiMove(GridCell(0, 5), GridCell(0, 3)))
        assertEquals(3, third.ply)
        assertEquals(5, third.pieceAt(0, 3))
        assertEquals(-5, third.pieceAt(1, 3))
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(third), snapshot(second)))
    }

    @Test fun skippedPliesAndUnrelatedBoardCorrectionsCannotInventOneMove() {
        val states = opening()
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(states[0]), snapshot(states[2])))
        val altered = states[1].copy(board = states[1].board.toMutableList().apply { this[6 * 9 + 2] = 0 })
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(states[0]), snapshot(altered)))
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(states[0]), snapshot(states[1].copy(outcome = XiangqiOutcome.RED_WON))))
    }

    @Test fun aGeometricallyMovedButIllegalRookIsNotAValidAnimation() {
        val before = XiangqiEngine.newGame()
        val illegal = XiangqiMove(GridCell(0, 9), GridCell(1, 8))
        val after = before.copy(board = before.board.toMutableList().apply { this[81] = 0; this[73] = 5 },
            turnSide = XiangqiSide.BLACK, ply = 1, lastMove = illegal)
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(before), snapshot(after)))
    }

    @Test fun aNewEpochOrRoundCannotReuseTheOldMoveSnapshot() {
        val states = opening()
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(states[0]), snapshot(states[1], key.copy(epoch = 4))))
        assertNull(XiangqiMoveAnimator.stepFrom(snapshot(states[0]), snapshot(states[1], key.copy(round = 2))))
    }

    @Test fun rapidAdjacentSnapshotsRemainOrderedWithoutReplacingTheActiveMove() {
        val states = opening()
        val queue = XiangqiMoveQueue(snapshot(states[0]))
        assertEquals(XiangqiQueueUpdate.ENQUEUED, queue.offer(snapshot(states[1])))
        val first = requireNotNull(queue.take())
        assertEquals(XiangqiQueueUpdate.ENQUEUED, queue.offer(snapshot(states[2])))
        assertEquals(XiangqiQueueUpdate.ENQUEUED, queue.offer(snapshot(states[3])))
        assertTrue(queue.owns(first))
        assertEquals(states[1], first.step.after)
        val second = requireNotNull(queue.take())
        assertEquals(states[1], second.step.before)
        assertEquals(states[2], second.step.after)
        assertEquals(states[3], queue.take()?.step?.after)
        assertFalse(queue.hasPending)
    }

    @Test fun undoThenReplayOfTheExactSamePositionInvalidatesTheOldCompletionLease() {
        val states = opening()
        val queue = XiangqiMoveQueue(snapshot(states[0]))
        queue.offer(snapshot(states[1]))
        val obsolete = requireNotNull(queue.take())
        assertEquals(XiangqiQueueUpdate.RESET, queue.offer(snapshot(states[0])))
        assertFalse(queue.owns(obsolete))
        assertEquals(XiangqiQueueUpdate.ENQUEUED, queue.offer(snapshot(states[1])))
        val replay = requireNotNull(queue.take())
        assertEquals(obsolete.step.after, replay.step.after)
        assertNotEquals(obsolete.generation, replay.generation)
        assertTrue(queue.owns(replay))
        assertFalse(queue.owns(obsolete))
    }

    @Test fun excessiveBacklogResetsToAuthorityAndInvalidatesAlreadyRunningArt() {
        val states = opening()
        val queue = XiangqiMoveQueue(snapshot(states[0]))
        queue.offer(snapshot(states[1]))
        val active = requireNotNull(queue.take())
        for (index in 2..5) assertEquals(XiangqiQueueUpdate.ENQUEUED, queue.offer(snapshot(states[index])))
        assertEquals(XiangqiQueueUpdate.RESET, queue.offer(snapshot(states[6])))
        assertFalse(queue.owns(active))
        assertFalse(queue.hasPending)
        assertNull(queue.take())
    }

    @Test fun initialTerminalRestoreAndRepeatedSnapshotsNeverQueueOldMoves() {
        val puzzle = XiangqiPuzzles.all.first()
        val final = XiangqiEngine.play(puzzle.position, puzzle.solution)
        val queue = XiangqiMoveQueue(snapshot(final))
        assertEquals(XiangqiQueueUpdate.UNCHANGED, queue.offer(snapshot(final)))
        assertNull(queue.take())
    }

    private fun opening(): List<XiangqiState> {
        val result = mutableListOf(XiangqiEngine.newGame())
        for (file in listOf(0, 2, 4)) {
            result += XiangqiEngine.play(result.last(), XiangqiMove(GridCell(file, 6), GridCell(file, 5)))
            result += XiangqiEngine.play(result.last(), XiangqiMove(GridCell(file, 3), GridCell(file, 4)))
        }
        return result
    }

    private fun sparse(vararg pieces: Pair<GridCell, Int>) = XiangqiState(board = MutableList(90) { 0 }.apply {
        this[85] = 1; this[3] = -1
        pieces.forEach { (cell, value) -> this[cell.y * 9 + cell.x] = value }
    })
}
