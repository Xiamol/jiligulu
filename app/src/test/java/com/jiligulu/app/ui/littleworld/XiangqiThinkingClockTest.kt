package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiThinkingClockTest {
    @Test fun pausedClockNeverChargesElapsedTime() {
        val clock = XiangqiThinkingClock().elapse(30_000, active = true)
        assertSame(clock, clock.elapse(90_000, active = false))
        assertEquals(90, clock.secondsRemaining)
    }

    @Test fun samePositionDoesNotRefreshTheCountdownButNextMoveDoes() {
        val game = XiangqiEngine.newGame()
        val clock = XiangqiThinkingClock.reset(game).elapse(72_000, active = true)
        assertSame(clock, clock.forPosition(game.copy(board = game.board.toList())))
        val next = XiangqiEngine.play(game, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        val nextClock = clock.forPosition(next)
        assertEquals(120, nextClock.secondsRemaining)
        assertEquals(XiangqiSide.BLACK, nextClock.side)
        assertEquals(next.ply, nextClock.ply)
    }

    @Test fun timeoutIsOnlyAReminderAndStopsAtZero() {
        val game = XiangqiEngine.newGame()
        val expired = XiangqiThinkingClock.reset(game).elapse(500_000, active = true)
        assertTrue(expired.expired)
        assertEquals(0, expired.secondsRemaining)
        assertSame(expired, expired.elapse(1000, active = true))
        assertEquals(XiangqiOutcome.PLAYING, game.outcome)
        assertTrue(XiangqiEngine.legalMoves(game).isNotEmpty())
    }

    @Test fun clockRoundsUpAndIgnoresNegativeElapsedValues() {
        val clock = XiangqiThinkingClock().elapse(1001, active = true)
        assertEquals(119, clock.secondsRemaining)
        assertSame(clock, clock.elapse(-1000, active = true))
        assertEquals(120, XiangqiThinkingClock.reset(XiangqiEngine.newGame()).secondsRemaining)
    }

    @Test fun configuredThinkingTimeResetsForEachTurnAndPauseKeepsIt() {
        val game = XiangqiEngine.newGame()
        val clock = XiangqiThinkingClock.reset(game, 45).elapse(5500, true)
        assertEquals(40, clock.secondsRemaining)
        assertSame(clock, clock.elapse(100_000, false))
        val next = XiangqiEngine.play(game, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        assertEquals(45, clock.forPosition(next).secondsRemaining)
        assertEquals(45_000L, clock.forPosition(next).durationMillis)
    }

    @Test fun thinkingTimeBoundsAreValidatedBeforeStarting() {
        val game = XiangqiEngine.newGame()
        assertEquals(15, XiangqiThinkingClock.reset(game, 15).secondsRemaining)
        assertEquals(600, XiangqiThinkingClock.reset(game, 600).secondsRemaining)
        for (invalid in listOf(-1, 0, 14, 601, Int.MAX_VALUE)) {
            try { XiangqiThinkingClock.reset(game, invalid); fail("accepted $invalid") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
