package com.jiligulu.app.ui.littleworld

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XiangqiLanProtocolTest {
    @Test fun initialAndPlayedBoardsRoundTripWithoutChangingTheirSignedPieces() {
        val initial = XiangqiEngine.newGame()
        val redMove = XiangqiMove(GridCell(0, 6), GridCell(0, 5))
        val redPlayed = XiangqiEngine.play(initial, redMove)
        val blackPlayed = XiangqiEngine.play(redPlayed, XiangqiMove(GridCell(0, 3), GridCell(0, 4)))
        assertEquals(2, blackPlayed.ply)
        for ((revision, game) in listOf(0 to initial, 1 to redPlayed, 23 to blackPlayed)) {
            val decoded = XiangqiWireCodec.decodeState(XiangqiWireCodec.encodeState(revision, game))
            assertEquals(revision, decoded.revision)
            assertEquals(game, decoded.game)
        }
        assertEquals(
            XiangqiLanMessage.Move(42, redMove),
            XiangqiWireCodec.decodeMove(XiangqiWireCodec.encodeMove(42, redMove)),
        )
    }

    @Test fun aRestartUsesANewRevisionAndResetsPlyToZero() {
        val snapshot = XiangqiWireCodec.decodeState(XiangqiWireCodec.encodeState(57, XiangqiEngine.newGame()))
        assertEquals(57, snapshot.revision)
        assertEquals(0, snapshot.game.ply)
        assertEquals(XiangqiSide.RED, snapshot.game.turnSide)
        assertNull(snapshot.game.lastMove)
    }

    @Test fun selectedSquaresAndExplicitClearsRoundTripWithinTheBoundedWireFormat() {
        for (hint in listOf(
            XiangqiLanMessage.Select(0, GridCell(0, 0)),
            XiangqiLanMessage.Select(17, GridCell(8, 9)),
            XiangqiLanMessage.Select(Int.MAX_VALUE, null),
        )) {
            val line = XiangqiLanProtocol.encode(hint)
            assertTrue(line.length <= XiangqiLanProtocol.MAX_LINE_BYTES)
            assertEquals(hint, XiangqiLanProtocol.decode(line))
        }
        for (line in listOf("XQ2|SELECT|0|-2", "XQ2|SELECT|0|90", "XQ2|SELECT|-1|0",
            "XQ2|SELECT|0|1|EXTRA", "XQ2|SELECT|2147483648|0")) {
            expectProtocolFailure { XiangqiLanProtocol.decode(line) }
        }
        for (cell in listOf(GridCell(-1, 0), GridCell(9, 9), GridCell(0, 10))) {
            try {
                XiangqiLanProtocol.encode(XiangqiLanMessage.Select(0, cell))
                fail("Out-of-bounds selection was encoded")
            } catch (_: IllegalArgumentException) { /* No out-of-board coordinates are sent. */ }
        }
    }

    @Test fun rejectsUnversionedMessagesExtraFieldsBadMovesAndUnknownCommands() {
        for (line in listOf(
            "XQ3|HELLO", "XQ2|HELLO|EXTRA", "XQ2|LAUNCH|anything",
            "XQ2|MOVE|-1|54|45", "XQ2|MOVE|1|90|45", "XQ2|MOVE|1|54|54",
            "XQ2|MOVE|2147483648|54|45", "XQ2|MOVE|1|54|45|EXTRA",
            "XQ2|REJECT|UNKNOWN", "XQ2|PING\u0000",
        )) expectProtocolFailure { XiangqiLanProtocol.decode(line) }
    }

    @Test fun validatesBoardLengthPieceRangeEnumsPlyAndLastMove() {
        val initial = XiangqiWireCodec.encodeState(0, XiangqiEngine.newGame())
        val board = initial.split('|')[8].split(',')
        val badPieceBoard = board.toMutableList().apply { this[0] = "8" }.joinToString(",")
        val missingBlackGeneral = board.toMutableList().apply { this[4] = "0" }.joinToString(",")
        for (line in listOf(
            field(initial, 8, board.dropLast(1).joinToString(",")),
            field(initial, 8, badPieceBoard),
            field(initial, 8, missingBlackGeneral),
            field(initial, 3, "PURPLE"),
            field(initial, 4, "DRAW"),
            field(initial, 4, "UNKNOWN_OUTCOME"),
            field(initial, 5, "-1"),
            field(initial, 5, "1000001"),
            field(initial, 3, "BLACK"),
            field(initial, 6, "54"),
            field(initial, 7, "90"),
        )) expectProtocolFailure { XiangqiLanProtocol.decode(line) }
        expectProtocolFailure { XiangqiWireCodec.decodeState("XQ2|PING") }
        expectProtocolFailure { XiangqiWireCodec.decodeMove(initial) }
    }

    @Test fun anOrdinaryStateNeverEndsAChessRoundWithoutDrawConsent() {
        val initial=XiangqiEngine.newGame()
        val played=XiangqiEngine.play(initial,XiangqiMove(GridCell(0,6),GridCell(0,5)))
        for(game in listOf(initial,played)) {
            val wire=XiangqiWireCodec.encodeState(7,game.copy(outcome=XiangqiOutcome.DRAW))
            expectProtocolFailure { XiangqiLanProtocol.decode(wire) }
            assertFalse(XiangqiSnapshotRules.accepts(6,game,true,
                XiangqiLanMessage.Snapshot(7,game.copy(outcome=XiangqiOutcome.DRAW)),allowRestart=false))
        }
        val offer=RoomDrawOffer(1,6,1,1)
        val accepted=RoomControl.DrawResult(offer,RoomDrawResolution.ACCEPTED,7)
        assertEquals(XiangqiLanMessage.Control(accepted),
            XiangqiLanProtocol.decode(XiangqiLanProtocol.encode(XiangqiLanMessage.Control(accepted))))
        assertFalse(RoomDrawRules.acceptsResult(1,6,offer,false,accepted))
        assertTrue(RoomDrawRules.acceptsResult(1,6,offer,true,accepted))
    }

    @Test fun acceptsOnlyPrivateNumericIpv4AndTheRoomsFixedPort() {
        for (address in listOf("192.168.1.8", "192.168.1.8:49761", "10.0.0.5", "172.16.0.1", "172.31.255.254")) {
            val endpoint = XiangqiLanProtocol.parseAddress(address)
            assertEquals(XiangqiLanSession.PORT, endpoint.port)
            assertTrue(endpoint.address.isSiteLocalAddress)
        }
        for (address in listOf("example.com", "8.8.8.8", "127.0.0.1", "172.15.0.1", "172.32.0.1",
            "192.168.1.256", "192.168.1.8:80", "192.168.1.8:49761:4", "::1", "0.0.0.0")) {
            try {
                XiangqiLanProtocol.parseAddress(address)
                fail("Accepted non-room address: $address")
            } catch (_: IllegalArgumentException) {
                // Expected: address parsing must never fall back to DNS or an Internet destination.
            }
        }
    }

    @Test fun fragmentedLineSurvivesAReadTimeoutAndNextLineStartsCleanly() {
        val bytes = "XQ2|MOVE|3|27|36\nXQ2|PING\n".toByteArray(Charsets.US_ASCII)
        val input = object : InputStream() {
            var position = 0
            var timedOut = false
            override fun read(): Int {
                if (position == 9 && !timedOut) {
                    timedOut = true
                    throw SocketTimeoutException("fragment delayed")
                }
                return if (position < bytes.size) bytes[position++].toInt() and 255 else -1
            }
        }
        val reader = BoundedLanLineReader(input)
        try {
            reader.readLine()
            fail("Expected the simulated read timeout")
        } catch (_: SocketTimeoutException) {
            // A subsequent read must resume the same line, not parse the suffix as a new message.
        }
        assertEquals("XQ2|MOVE|3|27|36", reader.readLine())
        assertEquals("XQ2|PING", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test fun boundsMemoryAndRejectsNonAsciiOrTruncatedLines() {
        for (bytes in listOf(
            ("A".repeat(XiangqiLanProtocol.MAX_LINE_BYTES + 1) + "\n").toByteArray(),
            byteArrayOf('X'.code.toByte(), 255.toByte(), '\n'.code.toByte()),
            "XQ2|MOVE|1|54".toByteArray(),
        )) expectProtocolFailure { BoundedLanLineReader(ByteArrayInputStream(bytes)).readLine() }
        expectProtocolFailure {
            XiangqiLanProtocol.decode("A".repeat(XiangqiLanProtocol.MAX_LINE_BYTES + 1))
        }
    }

    private fun field(line: String, index: Int, value: String): String =
        line.split('|').toMutableList().apply { this[index] = value }.joinToString("|")

    private fun expectProtocolFailure(block: () -> Unit) {
        try {
            block()
            fail("Malformed input was accepted")
        } catch (_: LanProtocolException) {
            // Expected bounded protocol rejection.
        }
    }
}
