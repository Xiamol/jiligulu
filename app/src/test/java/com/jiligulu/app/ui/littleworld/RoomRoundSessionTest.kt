package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercises real session authority and timers through a serialized in-memory data channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RoomRoundSessionTest {
    private val handler = Handler(Looper.getMainLooper())
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private inner class Wire(val events: GomokuWireEvents) : GomokuRoomWire {
        lateinit var peer: Wire
        var closed = false
        var hold = false
        val sent = mutableListOf<String>()
        override fun send(line: String) {
            sent += line
            if (::peer.isInitialized && !closed && !hold) handler.post { if (!peer.closed) peer.events.data(line) }
        }
        override fun close() { closed = true }
    }
    private inner class Channel {
        lateinit var host: Wire
        lateinit var guest: Wire
        val factory: (String, Boolean, GomokuWireEvents) -> GomokuRoomWire = { _, hosting, events ->
            Wire(events).also { if (hosting) host = it else guest = it }
        }
        fun connect() {
            host.peer = guest; guest.peer = host
            handler.post(host.events.connected); handler.post(guest.events.connected); drain()
        }
    }
    private fun gomoku(first: Int = 2): Triple<GomokuLanSession, GomokuLanSession, Channel> {
        val host = GomokuLanSession(); val guest = GomokuLanSession(); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { first }
        host.host(); guest.join("192.168.1.8"); channel.connect()
        assertEquals("棋友", host.state.value.pendingMatchName)
        host.respondToMatch(true); drain()
        return Triple(host, guest, channel)
    }

    @Test fun whiteHostStillAuthorizesBlackGuestMovesAndRequiresUndoConsent() {
        val (host, guest) = gomoku()
        assertTrue(host.state.value.isHost); assertEquals(2, host.state.value.localPlayer)
        assertEquals(1, guest.state.value.localPlayer)
        guest.submitMove(GridCell(7, 7)); drain()
        assertEquals(1, host.state.value.revision)
        assertEquals(host.state.value.game, guest.state.value.game)
        host.requestUndo(); drain()
        assertEquals(2, guest.state.value.pendingUndoRequest)
        assertEquals(1, host.state.value.game.cellAt(7, 7))
        guest.respondToUndo(false); drain()
        assertEquals(1, host.state.value.game.cellAt(7, 7))
        host.requestUndo(); drain(); guest.respondToUndo(true); drain()
        assertEquals(GomokuEngine.newGame(), host.state.value.game)
        assertEquals(host.state.value.game, guest.state.value.game)
        assertEquals(2, host.state.value.revision)
    }

    @Test fun rematchNeverRestartsUnilaterallyAndSwapsEveryTime() {
        val (host, guest, channel) = gomoku(1)
        host.submitMove(GridCell(7, 7)); drain()
        guest.requestRematch(); drain()
        assertEquals(1, host.state.value.round)
        assertEquals(1, host.state.value.game.cellAt(7, 7))
        host.respondToRematch(false); drain()
        assertNull(guest.state.value.rematchRequestedBy)
        host.requestRematch(); drain(); guest.respondToRematch(true); drain()
        assertEquals(2, host.state.value.round); assertEquals(2, host.state.value.localPlayer)
        assertEquals(1, guest.state.value.localPlayer); assertEquals(GomokuEngine.newGame(), guest.state.value.game)
        assertEquals(2, guest.state.value.revision)
        channel.guest.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(RoomControl.Vote(1, 1, true)))); drain()
        assertEquals(2, host.state.value.round)
        guest.requestRematch(); drain(); host.respondToRematch(true); drain()
        assertEquals(3, host.state.value.round); assertEquals(1, host.state.value.localPlayer)
        assertEquals(3, guest.state.value.revision)
    }

    @Test fun waitingNearbyPeersAutoMatchButExpiredWaitingNonceDoesNot() {
        val host = GomokuLanSession(); val guest = GomokuLanSession(); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 2 }
        host.host(); host.allowNearbyMatching("000000000001")
        guest.joinNearby(NearbyGameRoom("000000000001", "甲", "192.168.1.8", 49762), "000000000002", "乙")
        channel.connect()
        assertTrue(host.state.value.connected); assertEquals(1, guest.state.value.round)
        host.close(); guest.close()

        val staleHost = GomokuLanSession(); val staleGuest = GomokuLanSession(); val staleChannel = Channel()
        staleHost.wireFactory = staleChannel.factory; staleGuest.wireFactory = staleChannel.factory
        staleHost.host(); staleHost.allowNearbyMatching("000000000003")
        staleGuest.joinNearby(NearbyGameRoom("000000000001", "甲", "192.168.1.8", 49762), "000000000002")
        staleChannel.connect()
        assertFalse(staleHost.state.value.connected); assertFalse(staleGuest.state.value.sessionActive)
        assertEquals(0, staleGuest.state.value.round)
    }

    private fun finishGomoku(host: GomokuRoomSession, guest: GomokuRoomSession) {
        val black = if (host.state.value.localPlayer == 1) host else guest
        val white = if (black === host) guest else host
        for (x in 0..3) {
            black.submitMove(GridCell(x, 0)); drain()
            white.submitMove(GridCell(x, 2)); drain()
        }
        black.submitMove(GridCell(4, 0)); drain()
        assertEquals(GomokuOutcome.HUMAN_WON, host.state.value.game.outcome)
    }

    @Test fun terminalRoundTimesOutClosesBothWiresAndRetainsFinalBoard() {
        val (host, guest, channel) = gomoku()
        finishGomoku(host, guest)
        assertEquals(30, host.state.value.resultSecondsLeft)
        val finished = host.state.value.game
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertFalse(host.state.value.connected); assertFalse(guest.state.value.connected)
        assertTrue(channel.host.closed); assertTrue(channel.guest.closed)
        assertTrue(host.state.value.roomEnded); assertEquals(finished, guest.state.value.game)
        assertEquals(2, host.state.value.localPlayer)
        assertEquals(0, guest.state.value.resultSecondsLeft)
    }

    @Test fun terminalRematchResetsDeadlineAndRefusalClosesRoom() {
        val (host, guest) = gomoku()
        finishGomoku(host, guest)
        host.requestRematch(); drain(); guest.respondToRematch(true); drain()
        assertEquals(2, guest.state.value.round); assertEquals(0, guest.state.value.resultSecondsLeft)
        assertEquals(0, host.state.value.resultSecondsLeft)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(host.state.value.connected); assertEquals(2, guest.state.value.round)
        finishGomoku(host, guest)
        guest.respondToRematch(false); drain()
        assertFalse(host.state.value.sessionActive); assertFalse(guest.state.value.sessionActive)
        assertEquals(GomokuOutcome.HUMAN_WON, guest.state.value.game.outcome)
    }

    @Test fun internetCustomCodeIsPassedToTransportAndOpenRoomAcceptsJoin() {
        val context = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(context); val guest = GomokuOnlineSession(context); val channel = Channel()
        var createdCode = ""
        host.wireFactory = { code, hosting, events -> createdCode = code; channel.factory(code, hosting, events) }
        guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        host.host("alu2026"); guest.join("ALU2026"); channel.connect()
        assertEquals("ALU2026", createdCode)
        assertTrue(host.state.value.connected); assertEquals(2, guest.state.value.localPlayer)
        host.close(); drain()
        assertFalse(guest.state.value.connected)
    }

    @Test fun blackXiangqiHostSharesSelectionsAcceptsRedMoveAndNegotiatesNextRound() {
        val host = XiangqiLanSession(); val guest = XiangqiLanSession(); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 2 }
        host.host(); guest.join("192.168.1.8"); channel.connect(); host.respondToMatch(true); drain()
        assertEquals(XiangqiSide.BLACK, host.state.value.localSide)
        assertEquals(XiangqiSide.RED, guest.state.value.localSide)
        guest.selectPiece(GridCell(0, 6))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110))
        assertEquals(GridCell(0, 6), host.state.value.remoteSelection)
        guest.submitMove(XiangqiMove(GridCell(0, 6), GridCell(0, 5))); drain()
        assertEquals(1, host.state.value.revision); assertEquals(host.state.value.game, guest.state.value.game)
        assertNull(host.state.value.remoteSelection)
        host.requestUndo(); drain(); guest.respondToUndo(true); drain()
        assertEquals(XiangqiEngine.newGame(), host.state.value.game)
        guest.requestRematch(); drain(); host.respondToRematch(true); drain()
        assertEquals(XiangqiSide.RED, host.state.value.localSide)
        assertEquals(XiangqiSide.BLACK, guest.state.value.localSide)
        assertEquals(2, guest.state.value.round); assertEquals(3, guest.state.value.revision)
    }

    @Test fun guestCannotAcceptUnsolicitedFreshBoardAndPlayingDisconnectHasNoFakeWinner() {
        val (host, guest, channel) = gomoku(1)
        host.submitMove(GridCell(7, 7)); drain()
        val synced = guest.state.value.game
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Snapshot(2, GomokuEngine.newGame()))); drain()
        assertFalse(guest.state.value.connected)
        assertEquals(synced, guest.state.value.game)
        assertEquals(GomokuOutcome.PLAYING, guest.state.value.game.outcome)
        assertTrue(guest.state.value.roomEnded)
    }

    @Test fun hostCannotInventGuestConsentBySendingBothVotesAsReady() {
        val (host, guest, channel) = gomoku(1)
        host.submitMove(GridCell(7, 7)); drain()
        val synced = guest.state.value.game
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(RoomControl.Votes(1, 1, true, true))))
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(RoomControl.Start(RoomAssignment(2, 2, 2)))))
        drain()
        assertFalse(guest.state.value.connected)
        assertEquals(synced, guest.state.value.game)
        assertEquals(1, guest.state.value.round)
    }

    @Test fun guestOwnedMoveMustMatchItsExactSubmissionAndDuplicateStateDoesNotAckIt() {
        val (_, guest, channel) = gomoku(2)
        channel.guest.hold = true
        guest.submitMove(GridCell(7, 7))
        assertTrue(guest.state.value.awaitingAck)
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Snapshot(0, GomokuEngine.newGame()))); drain()
        assertTrue(guest.state.value.awaitingAck)
        val invented = GomokuEngine.play(GomokuEngine.newGame(), 8, 8)
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Snapshot(1, invented))); drain()
        assertFalse(guest.state.value.connected)
        assertEquals(GomokuEngine.newGame(), guest.state.value.game)
    }

    @Test fun hostCannotInventARedXiangqiMoveOnTheGuestsBehalf() {
        val host = XiangqiLanSession(); val guest = XiangqiLanSession(); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 2 }
        host.host(); guest.join("192.168.1.8"); channel.connect(); host.respondToMatch(true); drain()
        val invented = XiangqiEngine.play(XiangqiEngine.newGame(), XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        channel.host.send(XiangqiLanProtocol.encode(XiangqiLanMessage.Snapshot(1, invented))); drain()
        assertFalse(guest.state.value.connected)
        assertEquals(XiangqiEngine.newGame(), guest.state.value.game)
    }

    @Test fun simultaneousRematchVotesDoNotLoseGuestConsentInEarlierHostReply() {
        val (host, guest) = gomoku(1)
        host.requestRematch(); guest.requestRematch(); drain()
        assertTrue(host.state.value.connected); assertTrue(guest.state.value.connected)
        assertEquals(2, host.state.value.round); assertEquals(2, guest.state.value.round)
        assertEquals(2, host.state.value.localPlayer); assertEquals(1, guest.state.value.localPlayer)

        val xHost = XiangqiLanSession(); val xGuest = XiangqiLanSession(); val channel = Channel()
        xHost.wireFactory = channel.factory; xGuest.wireFactory = channel.factory; xHost.firstPlayer = { 2 }
        xHost.host(); xGuest.join("192.168.1.8"); channel.connect(); xHost.respondToMatch(true); drain()
        xHost.requestRematch(); xGuest.requestRematch(); drain()
        assertTrue(xGuest.state.value.connected); assertEquals(2, xGuest.state.value.round)
        assertEquals(XiangqiSide.RED, xHost.state.value.localSide)
    }

    @Test fun crossedUndoAndRematchDoesNotLeaveGuestStuckInUnacknowledgedVote() {
        val (host, guest) = gomoku(1)
        host.submitMove(GridCell(7, 7)); drain()
        host.requestUndo(); guest.requestRematch(); drain()
        assertNotNull(guest.state.value.pendingUndoRequest)
        assertFalse(guest.state.value.myRematchRequested)
        assertNull(guest.state.value.rematchRequestedBy)
        guest.respondToUndo(false); drain()
        assertTrue(guest.state.value.connected)
        assertNull(guest.state.value.pendingUndoRequest)
        guest.submitMove(GridCell(7, 8)); drain()
        assertEquals(2, host.state.value.revision)
    }

    @Test fun activeNearbyWaitingDoesNotExpireBeforeAFriendEnters() {
        val host = GomokuLanSession(); val channel = Channel()
        host.wireFactory = channel.factory
        host.host(); host.allowNearbyMatching("000000000001")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertTrue(host.state.value.sessionActive)
        assertFalse(channel.host.closed)
        val guest = GomokuLanSession(); guest.wireFactory = channel.factory
        guest.joinNearby(NearbyGameRoom("000000000001", "甲", "192.168.1.8", 49762), "000000000002")
        channel.connect()
        assertTrue(host.state.value.connected); assertTrue(guest.state.value.connected)
    }
}
