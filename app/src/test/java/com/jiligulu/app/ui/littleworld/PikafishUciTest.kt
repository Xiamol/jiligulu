package com.jiligulu.app.ui.littleworld

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PikafishUciTest {
    @Test fun openingFenUsesBlackAtTheTopAndStandardPieceLetters() {
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
            PikafishUci.fen(XiangqiEngine.newGame()))
    }

    @Test fun fenTracksTurnAndFullMoveNumberWithoutChangingThePosition() {
        val red = XiangqiEngine.play(XiangqiEngine.newGame(), XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        val black = XiangqiEngine.play(red, XiangqiMove(GridCell(0, 3), GridCell(0, 4)))
        val before = black.board.toList()
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/P8/2P1P1P1P/1C5C1/9/RNBAKABNR b - - 0 1",
            PikafishUci.fen(red))
        assertTrue(PikafishUci.fen(black).endsWith(" w - - 0 2"))
        assertEquals(before, black.board)
    }

    @Test fun coordinatesAreNotFlippedForThePlayerHoldingBlack() {
        assertEquals(XiangqiMove(GridCell(0, 9), GridCell(8, 0)), PikafishUci.move("a0i9"))
        assertEquals(XiangqiMove(GridCell(0, 3), GridCell(0, 4)), PikafishUci.move("a6a5"))
        for (y in 0..9) for (x in 0..8) {
            val targetX = (x + 1) % 9
            val text = "${'a' + x}${9 - y}${'a' + targetX}${9 - y}"
            assertEquals(XiangqiMove(GridCell(x, y), GridCell(targetX, y)), PikafishUci.move(text))
        }
    }

    @Test fun malformedWrongSideAndIllegalBestMovesAreRejected() {
        val state = XiangqiEngine.newGame()
        for (token in listOf("(none)", "0000", "a0a0", "j0a0", "a:a0", "a0a1q", "A0a1")) {
            assertNull(token, PikafishUci.move(token))
        }
        for (line in listOf("info string bestmove a3a4", "bestmove", "bestmove (none)", "bestmove a6a5", "bestmove a0a9")) {
            assertNull(line, PikafishUci.bestMove(line, state))
        }
        assertEquals(XiangqiMove(GridCell(0, 6), GridCell(0, 5)),
            PikafishUci.bestMove("bestmove a3a4 ponder a6a5", state))
        assertNull(PikafishUci.bestMove("bestmove a3a4", state.copy(outcome = XiangqiOutcome.RED_WON)))
    }

    @Test fun protocolWaitsForReadinessAndReturnsOnlyTheLegalBestMove() {
        val fake = FakeTransport().apply {
            reply = { command -> when {
                command == "uci" -> listOf("id name Pikafish", "uciok")
                command == "isready" -> listOf("readyok")
                command.startsWith("go ") -> listOf("info depth 12 nodes 5000", "bestmove a3a4 ponder a6a5")
                else -> emptyList()
            } }
        }
        assertEquals(XiangqiMove(GridCell(0, 6), GridCell(0, 5)),
            PikafishUciSession(fake, { fake.time }).chooseMove(XiangqiEngine.newGame(), "/private/net/pikafish.nnue", 1500) { false })
        assertEquals(listOf("uci", "setoption name Threads value 1", "setoption name Hash value 32",
            "setoption name Ponder value false", "setoption name EvalFile value /private/net/pikafish.nnue", "isready",
            "position fen ${PikafishUci.fen(XiangqiEngine.newGame())}", "go movetime 1500"), fake.commands)
        assertTrue(fake.closed)
    }

    @Test fun cancellationWhileWaitingForHandshakeClosesTheTransportWithoutStartingSearch() {
        val fake = FakeTransport()
        val move = PikafishUciSession(fake, { fake.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) { fake.time >= 75 }
        assertNull(move)
        assertTrue(fake.closed)
        assertEquals(listOf("uci"), fake.commands)
        assertEquals(75, fake.time)
    }

    @Test fun cancellationDuringSearchDiscardsEvenAnAlreadyQueuedBestMove() {
        val fake = FakeTransport().apply {
            reply = { command -> when {
                command == "uci" -> listOf("uciok")
                command == "isready" -> listOf("readyok")
                command.startsWith("go ") -> listOf("bestmove a3a4")
                else -> emptyList()
            } }
        }
        assertNull(PikafishUciSession(fake, { fake.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) {
            fake.commands.any { it.startsWith("go ") }
        })
        assertTrue(fake.closed)
    }

    @Test fun timeoutsAreBoundedAndDoNotWaitForeverForNativeOutput() {
        val handshake = FakeTransport()
        assertNull(PikafishUciSession(handshake, { handshake.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) { false })
        assertEquals(6000, handshake.time)
        assertTrue(handshake.closed)
        val search = FakeTransport().apply { reply = { when(it) { "uci" -> listOf("uciok"); "isready" -> listOf("readyok"); else -> emptyList() } } }
        assertNull(PikafishUciSession(search, { search.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) { false })
        assertEquals(2500, search.time)
        assertTrue(search.closed)
    }

    @Test fun nativeCriticalErrorEofAndWriteFailureAllCloseTheSession() {
        for (event in listOf(PikafishUciEvent.Line("info string CRITICAL ERROR invalid FEN"),
            PikafishUciEvent.Closed, PikafishUciEvent.Failed)) {
            val fake = FakeTransport().apply { events.add(event) }
            assertNull(PikafishUciSession(fake, { fake.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) { false })
            assertTrue(fake.closed)
        }
        val broken = FakeTransport().apply { writeFailure = true }
        assertNull(PikafishUciSession(broken, { broken.time }).chooseMove(XiangqiEngine.newGame(), "/net", 1500) { false })
        assertTrue(broken.closed)
    }

    private class FakeTransport : PikafishUciTransport {
        val commands = mutableListOf<String>()
        val events = ArrayDeque<PikafishUciEvent>()
        var reply: (String) -> List<String> = { emptyList() }
        var time = 0L
        var closed = false
        var writeFailure = false
        override fun writeLine(command: String) {
            if (writeFailure) throw IOException("test write failure")
            commands += command
            reply(command).forEach { events.add(PikafishUciEvent.Line(it)) }
        }
        override fun poll(waitMillis: Long): PikafishUciEvent? {
            if (events.isNotEmpty()) return events.removeFirst()
            time += waitMillis
            return null
        }
        override fun close() { closed = true }
    }
}
