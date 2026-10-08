package com.jiligulu.app.ui.littleworld

import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class RapfiProtocolTest {
    @Test fun recordedBlackTurnKeepsBothColoursAndTheActualLastWhiteStone() {
        // Snapshot immediately before M9, captured from the 18-ply manual game.
        val state = position(
            black = listOf(49, 96, 98, 112, 114, 128, 129, 130, 131),
            white = listOf(65, 80, 81, 82, 97, 99, 113, 126, 127),
            current = 1, last = 127)
        assertEquals(9, state.board.count { it == 1 })
        assertEquals(9, state.board.count { it == 2 })
        assertNativePosition(state)
        assertEquals("7,8,2", requireNotNull(RapfiProtocol.boardLines(state)).dropLast(1).last())
    }

    @Test fun recordedWhiteTurnUsesSelfForWhiteAndDoesNotMirrorCoordinates() {
        // Snapshot immediately before H2, captured from the 45-ply manual game.
        val state = position(
            black = listOf(52, 57, 62, 68, 70, 71, 72, 76, 81, 84, 85, 86, 92, 95, 97, 98, 100, 109, 111, 112, 122, 124, 142),
            white = listOf(36, 50, 55, 56, 58, 64, 66, 69, 77, 78, 79, 80, 82, 87, 94, 96, 99, 108, 110, 113, 116, 126),
            current = 2, last = 92)
        assertEquals(23, state.board.count { it == 1 })
        assertEquals(22, state.board.count { it == 2 })
        assertNativePosition(state)
        val lines = requireNotNull(RapfiProtocol.boardLines(state))
        assertTrue(lines[1].endsWith(",2"))
        assertEquals("2,6,2", lines.dropLast(1).last())
        assertEquals(GridCell(7, 13), RapfiProtocol.move("7,13", state))
    }

    @Test fun emptyAndAnalysisPositionsAnchorBlackAndRequestTheExplicitSide() {
        for (current in 1..2) {
            val empty = GomokuState(currentPlayer = current)
            assertNativePosition(empty)
            assertEquals(if (current == 1) listOf("BOARD", "DONE")
                else listOf("BOARD", "-1,-1,2", "DONE"), RapfiProtocol.boardLines(empty))
            assertNativePosition(position(emptyList(), listOf(112), current, 112))
            assertNativePosition(position(listOf(112, 113, 114), emptyList(), current, 114))
        }
    }

    @Test fun malformedOccupiedOutOfBoundsAndTerminalCoordinatesCannotBecomeMoves() {
        val state = position(listOf(112), emptyList(), current = 2, last = 112)
        for (line in listOf("", "7", "7,7,2", "x,8", "8,NaN", "-1,0", "0,-1", "15,0", "0,15", "2147483648,0", "7,7")) {
            assertNull("Unexpected native move: $line", RapfiProtocol.move(line, state))
        }
        assertEquals(GridCell(8, 7), RapfiProtocol.move("  +8 , 7  ", state))
        assertNull(RapfiProtocol.move("8,7", state.copy(outcome = GomokuOutcome.DRAW)))
        assertNull(RapfiProtocol.boardLines(state.copy(outcome = GomokuOutcome.HUMAN_WON)))
        assertNull(RapfiProtocol.boardLines(GomokuState(size = 23)))
        assertNull(RapfiProtocol.boardLines(state.copy(board = state.board.toMutableList().apply { this[0] = 9 })))
        assertNull(RapfiProtocol.boardLines(GomokuState(board = List(225) { 1 })))
    }

    @Test fun successfulSessionUsesBoundedOfflineOptionsAndStopsExactlyOnce() {
        val clock = Clock()
        val io = ScriptedTransport(clock).apply {
            afterPoll = { event -> when ((event as? RapfiEvent.Line)?.text) {
                ABOUT -> clock.now += 40
                "OK" -> clock.now += 60
                "8,7" -> clock.now += 20
                else -> Unit
            } }
        }
        val session = RapfiSession(io, nowMillis = { clock.now })
        assertEquals(GridCell(8, 7), session.chooseMove(GomokuState(), 1_000) { false })
        assertEquals(RapfiStatus.PLAYED, session.status)
        assertEquals("0.43.01", session.version)
        assertEquals(100L, session.setupMillis)
        assertEquals(20L, session.searchMillis)
        assertEquals(listOf("ABOUT", "START 15"), io.commands.take(2))
        assertTrue("INFO RULE 0" in io.commands)
        assertTrue("INFO THREAD_NUM 1" in io.commands)
        assertTrue("INFO MAX_MEMORY 67108864" in io.commands)
        assertTrue("INFO PONDERING 0" in io.commands)
        assertTrue("INFO TIMEOUT_TURN 800" in io.commands)
        assertEquals(listOf("BOARD", "DONE"), io.commands.subList(io.commands.indexOf("BOARD"), io.commands.indexOf("DONE") + 1))
        assertCleanedUp(io)
    }

    @Test fun startupErrorsClosedStreamAndInvalidAnswersNeverPlay() {
        val startupFailures = listOf(RapfiEvent.Line("ERROR missing model"), RapfiEvent.Line("UNKNOWN ABOUT"),
            RapfiEvent.Line("MESSAGE CRITICAL ERROR invalid network"), RapfiEvent.Closed, RapfiEvent.Failed)
        for (failure in startupFailures) {
            for (atAbout in listOf(true, false)) {
                val clock = Clock()
                val io = ScriptedTransport(clock).apply {
                    if (atAbout) aboutEvent = failure else startEvent = failure
                }
                val session = RapfiSession(io, nowMillis = { clock.now })
                assertNull(session.chooseMove(GomokuState(), 1_000) { false })
                assertEquals(RapfiStatus.PROTOCOL_FAILED, session.status)
                assertFalse("BOARD" in io.commands)
                assertCleanedUp(io)
            }
        }
        for (reply in listOf("15,0", "-1,8", "7,7", "999999999999999999999,0")) {
            val clock = Clock()
            val io = ScriptedTransport(clock).apply { answer = RapfiEvent.Line(reply) }
            val session = RapfiSession(io, nowMillis = { clock.now })
            assertNull(session.chooseMove(position(listOf(112), emptyList(), 2, 112), 1_000) { false })
            assertEquals(RapfiStatus.PROTOCOL_FAILED, session.status)
            assertCleanedUp(io)
        }
    }

    @Test fun endOfSearchStreamAndWriteFailureStillCloseTheSession() {
        for (failure in listOf(RapfiEvent.Closed, RapfiEvent.Failed)) {
            val clock = Clock()
            val io = ScriptedTransport(clock).apply { answer = failure }
            val session = RapfiSession(io, nowMillis = { clock.now })
            assertNull(session.chooseMove(GomokuState(), 1_000) { false })
            assertEquals(RapfiStatus.PROTOCOL_FAILED, session.status)
            assertCleanedUp(io)
        }
        val clock = Clock()
        val io = ScriptedTransport(clock).apply {
            afterWrite = { if (it.startsWith("START")) throw IOException("closed pipe") }
        }
        val session = RapfiSession(io, nowMillis = { clock.now })
        assertNull(session.chooseMove(GomokuState(), 1_000) { false })
        assertEquals(RapfiStatus.PROTOCOL_FAILED, session.status)
        assertCleanedUp(io)
    }

    @Test fun unrelatedNativeMessagesAreNotMistakenForAnAnswer() {
        val clock = Clock()
        val io = ScriptedTransport(clock).apply {
            afterWrite = { if (it == "DONE") {
                events.addFirst(RapfiEvent.Line("MESSAGE depth 8"))
                events.addFirst(RapfiEvent.Line("not,a,move"))
            } }
        }
        val session = RapfiSession(io, nowMillis = { clock.now })
        assertEquals(GridCell(8, 7), session.chooseMove(GomokuState(), 1_000) { false })
        assertEquals(RapfiStatus.PLAYED, session.status)
        assertCleanedUp(io)
    }

    @Test fun deadlineStopsSilentNativeAndRejectsALateQueuedCoordinate() {
        for (lateCoordinate in listOf(false, true)) {
            val clock = Clock()
            val io = ScriptedTransport(clock).apply {
                if (lateCoordinate) afterPoll = { if ((it as? RapfiEvent.Line)?.text == "8,7") clock.now = 1_000 }
                else answer = null
            }
            val session = RapfiSession(io, nowMillis = { clock.now })
            assertNull(session.chooseMove(GomokuState(), 1_000) { false })
            assertEquals(RapfiStatus.TIMED_OUT, session.status)
            assertEquals(1_000L, clock.now)
            assertTrue(io.waits.all { it in 1L..25L })
            assertCleanedUp(io)
        }
    }

    @Test fun setupAndAbsoluteDeadlineConsumeTheSameTotalBudget() {
        val clock = Clock()
        val io = ScriptedTransport(clock).apply {
            afterPoll = { if ((it as? RapfiEvent.Line)?.text == "OK") clock.now = 930 }
        }
        val session = RapfiSession(io, nowMillis = { clock.now })
        assertNull(session.chooseMove(GomokuState(), 1_000) { false })
        assertEquals(RapfiStatus.TIMED_OUT, session.status)
        assertFalse("BOARD" in io.commands)
        assertCleanedUp(io)
        val expiredClock = Clock(250)
        val expiredIo = ScriptedTransport(expiredClock)
        val expiredSession = RapfiSession(expiredIo, nowMillis = { expiredClock.now }, absoluteDeadlineMillis = 200)
        assertNull(expiredSession.chooseMove(GomokuState(), 5_000) { false })
        assertEquals(RapfiStatus.TIMED_OUT, expiredSession.status)
        assertFalse(expiredIo.commands.any { it.startsWith("START") })
        assertCleanedUp(expiredIo)
    }

    @Test fun oneShotCancellationBeforeStartDuringSetupAndAfterAnswerIsSticky() {
        for (phase in listOf("before", "setup", "answer", "board")) {
            val clock = Clock()
            var pending = phase == "before"
            val io = ScriptedTransport(clock).apply {
                afterPoll = { event ->
                    if (phase == "setup" && (event as? RapfiEvent.Line)?.text == ABOUT ||
                        phase == "answer" && (event as? RapfiEvent.Line)?.text == "8,7") pending = true
                }
                afterWrite = { if (phase == "board" && it == "BOARD") pending = true }
            }
            val session = RapfiSession(io, nowMillis = { clock.now })
            assertNull(phase, session.chooseMove(GomokuState(), 1_000) {
                pending.also { pending = false }
            })
            assertEquals(phase, RapfiStatus.CANCELLED, session.status)
            assertFalse(pending)
            assertCleanedUp(io)
        }
    }

    private fun assertNativePosition(state: GomokuState) {
        val lines = requireNotNull(RapfiProtocol.boardLines(state))
        assertEquals("BOARD", lines.first())
        assertEquals("DONE", lines.last())
        val entries = lines.drop(1).dropLast(1).map { line -> line.split(',').map(String::toInt) }
        // Read SELF/OPPO as the native protocol does, independently of board serialization.
        val selfColour = if (entries.firstOrNull()?.get(2) == 2) 2 else 1
        var nextColour = 1
        val restored = MutableList(state.size * state.size) { 0 }
        for ((x, y, relativeSide) in entries) {
            assertTrue(relativeSide in 1..2)
            val colour = if (relativeSide == 1) selfColour else 3 - selfColour
            if (nextColour != colour) nextColour = 3 - nextColour // Native analysis PASS.
            if (x >= 0 && y >= 0) {
                assertTrue(x < state.size && y < state.size)
                assertEquals(0, restored[y * state.size + x])
                restored[y * state.size + x] = colour
            } else assertEquals(listOf(-1, -1), listOf(x, y))
            nextColour = 3 - nextColour
        }
        assertEquals(state.currentPlayer, selfColour)
        assertEquals(state.currentPlayer, nextColour)
        assertEquals(state.board, restored)
    }

    private fun position(black: List<Int>, white: List<Int>, current: Int, last: Int) = GomokuState(
        board = MutableList(225) { 0 }.apply { black.forEach { this[it] = 1 }; white.forEach { this[it] = 2 } },
        currentPlayer = current, lastMove = GridCell(last % 15, last / 15))

    private fun assertCleanedUp(io: ScriptedTransport) {
        assertEquals(listOf("STOP", "END"), io.commands.takeLast(2))
        assertEquals(1, io.commands.count { it == "STOP" })
        assertEquals(1, io.commands.count { it == "END" })
        assertEquals(1, io.closed)
    }

    private class Clock(var now: Long = 0)
    private class ScriptedTransport(private val clock: Clock) : RapfiTransport {
        val commands = ArrayList<String>()
        val events = ArrayDeque<RapfiEvent>()
        val waits = ArrayList<Long>()
        var aboutEvent: RapfiEvent = RapfiEvent.Line(ABOUT)
        var startEvent: RapfiEvent = RapfiEvent.Line("OK")
        var answer: RapfiEvent? = RapfiEvent.Line("8,7")
        var afterWrite: (String) -> Unit = {}
        var afterPoll: (RapfiEvent?) -> Unit = {}
        var closed = 0
        override fun writeLine(command: String) {
            commands += command
            when {
                command == "ABOUT" -> events.add(aboutEvent)
                command.startsWith("START ") -> events.add(startEvent)
                command == "DONE" -> answer?.let(events::add)
            }
            afterWrite(command)
        }
        override fun poll(waitMillis: Long): RapfiEvent? {
            waits += waitMillis
            val event = events.pollFirst()
            if (event == null) clock.now += waitMillis
            afterPoll(event)
            return event
        }
        override fun close() { closed++ }
    }

    companion object { private const val ABOUT = "name=\"Rapfi\", version=\"0.43.01\", author=\"Rapfi Developers\"" }
}
