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
        assertFalse(host.state.value.canUndo) // White has not yet made an own move.
        guest.requestUndo(); drain()
        assertEquals(1, host.state.value.pendingUndoRequest)
        assertEquals(1, host.state.value.game.cellAt(7, 7))
        host.respondToUndo(false); drain()
        assertEquals(1, host.state.value.game.cellAt(7, 7))
        guest.requestUndo(); drain(); host.respondToUndo(true); drain()
        assertEquals(GomokuEngine.newGame(), host.state.value.game)
        assertEquals(host.state.value.game, guest.state.value.game)
        assertEquals(2, host.state.value.revision)
    }

    @Test fun gomokuDrawNeedsConsentAndThenSupportsAnOrdinarySwappedRematch() {
        val(host,guest)=gomoku(1)
        host.submitMove(GridCell(7,7));drain()
        val before=host.state.value.game;val revision=host.state.value.revision
        host.requestDraw();drain()
        assertEquals(1,guest.state.value.pendingDrawRequest)
        guest.submitMove(GridCell(8,7));host.requestUndo();drain()
        assertEquals(before,host.state.value.game)
        guest.respondToDraw(false);drain()
        assertNull(host.state.value.pendingDrawRequest);assertEquals(revision,guest.state.value.revision)
        host.requestDraw();drain();guest.respondToDraw(true);drain()
        assertEquals(GomokuOutcome.DRAW,host.state.value.game.outcome)
        assertEquals(host.state.value.game,guest.state.value.game)
        assertEquals(before.board,guest.state.value.game.board)
        assertEquals(revision+1,guest.state.value.revision);assertTrue(guest.state.value.agreedDraw)
        host.requestRematch();drain();guest.respondToRematch(true);drain()
        assertEquals(2,host.state.value.round);assertEquals(2,host.state.value.localPlayer)
        assertFalse(guest.state.value.agreedDraw);assertNull(guest.state.value.pendingDrawRequest)
        assertEquals(GomokuEngine.newGame(),guest.state.value.game)
        host.close();guest.close()
    }

    @Test fun cancelledAndReplayedDrawRequestsNeverCancelANewerNonce() {
        val(host,guest,channel)=gomoku(1)
        guest.requestDraw();drain()
        val oldRequest=channel.guest.sent.last {it.contains("|DRAW_REQUEST|")}
        guest.cancelDraw();drain()
        val oldResult=channel.host.sent.last {it.contains("|DRAW_RESULT|")}
        assertNull(host.state.value.pendingDrawRequest)
        guest.requestDraw();drain();val id=host.state.value.pendingDrawId
        channel.guest.send(oldRequest);channel.host.send(oldResult);drain()
        assertEquals(id,host.state.value.pendingDrawId);assertEquals(id,guest.state.value.pendingDrawId)
        host.respondToDraw(false);drain()
        assertEquals(GomokuOutcome.PLAYING,host.state.value.game.outcome)
        assertNull(guest.state.value.pendingDrawRequest)
        host.close();guest.close()
    }

    @Test fun simultaneousDrawRequestsAgreeOnceAndDoNotDeadlock() {
        val(host,guest)=gomoku(1)
        host.requestDraw();guest.requestDraw();drain()
        assertEquals(GomokuOutcome.DRAW,host.state.value.game.outcome)
        assertEquals(host.state.value.game,guest.state.value.game)
        assertEquals(1,host.state.value.revision);assertEquals(1,guest.state.value.revision)
        assertNull(host.state.value.pendingDrawRequest);assertNull(guest.state.value.pendingDrawRequest)
        host.close();guest.close()
    }

    @Test fun cancellationAndAcceptanceRaceResolvesByHostOrderOnBothDesks() {
        val(host,guest)=gomoku(1)
        host.requestDraw();drain();guest.respondToDraw(true);host.cancelDraw();drain()
        assertEquals(GomokuOutcome.PLAYING,guest.state.value.game.outcome)
        assertNull(guest.state.value.pendingDrawRequest)
        guest.requestDraw();drain();host.respondToDraw(true);guest.cancelDraw();drain()
        assertEquals(GomokuOutcome.DRAW,guest.state.value.game.outcome)
        assertEquals(host.state.value.game,guest.state.value.game)
        host.close();guest.close()
    }

    @Test fun drawTimeoutPausesInBackgroundAndDepartureKeepsTheOriginalBoard() {
        val(host,guest)=gomoku(1)
        host.requestDraw();drain()
        guest.setForeground(false);drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertNotNull(host.state.value.pendingDrawRequest)
        guest.setForeground(true);drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertNull(host.state.value.pendingDrawRequest);assertNull(guest.state.value.pendingDrawRequest)
        assertEquals(GomokuOutcome.PLAYING,host.state.value.game.outcome)
        host.requestDraw();drain();val board=guest.state.value.game
        host.close();drain();assertTrue(guest.state.value.peerLeft)
        assertEquals(board,guest.state.value.game);assertNull(guest.state.value.pendingDrawRequest)
        guest.close()
    }

    @Test fun xiangqiDrawPreservesAllPiecesAndRejectsOldRoundReplay() {
        val host=XiangqiLanSession();val guest=XiangqiLanSession();val channel=Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={1}
        host.host();guest.join("192.168.1.8");channel.connect();host.respondToMatch(true);drain()
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5)));drain()
        val before=host.state.value.game
        guest.requestDraw();drain();val oldRequest=channel.guest.sent.last {it.contains("|DRAW_REQUEST|")}
        host.respondToDraw(false);drain();assertEquals(before,guest.state.value.game)
        guest.requestDraw();drain();host.respondToDraw(true);drain()
        assertEquals(XiangqiOutcome.DRAW,host.state.value.game.outcome)
        assertEquals(before.board,guest.state.value.game.board);assertEquals(before.ply,guest.state.value.game.ply)
        assertEquals(host.state.value.game,guest.state.value.game)
        host.requestRematch();drain();guest.respondToRematch(true);drain()
        channel.guest.send(oldRequest);drain()
        assertTrue(host.state.value.connected);assertEquals(2,host.state.value.round)
        assertEquals(XiangqiOutcome.PLAYING,guest.state.value.game.outcome)
        host.close();guest.close()
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
        guest.requestUndo(); drain(); host.respondToUndo(true); drain()
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

    @Test fun onlineInvitationWaitsFiveMinutesInsteadOfTwo() {
        val host = GomokuOnlineSession(RuntimeEnvironment.getApplication()); val channel = Channel()
        host.wireFactory = channel.factory; host.host("ALU2026")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertTrue(host.state.value.sessionActive)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(179))
        assertTrue(host.state.value.sessionActive)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(host.state.value.sessionActive); assertTrue(channel.host.closed)
    }

    @Test fun consecutiveUndoRollsBackTheRequestersMoveAndItsReplyInOneConsent() {
        val (host, guest) = gomoku(1)
        host.submitMove(GridCell(7,7)); drain(); guest.submitMove(GridCell(8,7)); drain()
        host.submitMove(GridCell(7,8)); drain(); guest.submitMove(GridCell(8,8)); drain()
        host.requestUndo(); drain(); guest.respondToUndo(true); drain()
        assertEquals(2, host.state.value.game.board.count { it != 0 })
        assertEquals(1, host.state.value.game.currentPlayer)
        host.requestUndo(); drain(); guest.respondToUndo(true); drain()
        assertEquals(GomokuEngine.newGame(), host.state.value.game)
        assertEquals(host.state.value.game, guest.state.value.game)
        assertFalse(host.state.value.canUndo)
        guest.requestUndo(); drain(); assertNull(host.state.value.pendingUndoRequest)
    }

    @Test fun backgroundPresencePausesTheOpponentWithoutErasingOrLosingTheRound() {
        val (host, guest, channel) = gomoku(1)
        guest.setForeground(false); drain()
        assertTrue(host.state.value.remoteBackground); assertTrue(guest.state.value.localBackground)
        host.submitMove(GridCell(7,7)); drain(); assertEquals(0, host.state.value.revision)
        channel.host.hold = true; channel.guest.hold = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(125))
        assertTrue(host.state.value.connected); assertTrue(guest.state.value.connected)
        assertEquals(GomokuOutcome.PLAYING, host.state.value.game.outcome)
        channel.host.hold = false; channel.guest.hold = false
        guest.setForeground(true); drain()
        assertFalse(host.state.value.remoteBackground)
        host.submitMove(GridCell(7,7)); drain()
        guest.submitMove(GridCell(8,8)); drain()
        assertEquals(2, host.state.value.revision); assertEquals(host.state.value.game, guest.state.value.game)
    }

    @Test fun aTransportPauseRetainsTheBoardAndRecoveryContinuesExactRevisions() {
        val (host, guest, channel) = gomoku(1)
        host.submitMove(GridCell(7,7)); drain()
        channel.host.hold = true; channel.guest.hold = true
        channel.host.events.recovering(); channel.guest.events.recovering()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertTrue(host.state.value.connected); assertTrue(host.state.value.reconnecting)
        guest.submitMove(GridCell(8,8)); drain(); assertEquals(1, host.state.value.revision)
        channel.host.hold = false; channel.guest.hold = false
        channel.host.events.recovered(); channel.guest.events.recovered(); drain()
        guest.submitMove(GridCell(8,8)); drain()
        assertEquals(2, host.state.value.revision); assertEquals(host.state.value.game, guest.state.value.game)
    }

    @Test fun eitherColorCanResignAndAgreedRematchClearsResignation() {
        for (hostColor in 1..2) {
            val (host, guest) = gomoku(hostColor)
            guest.resign(); drain()
            assertEquals(3-hostColor, host.state.value.resignedBy)
            assertEquals(host.state.value.game, guest.state.value.game)
            assertEquals(if(hostColor==1) GomokuOutcome.HUMAN_WON else GomokuOutcome.CPU_WON, host.state.value.game.outcome)
            host.requestRematch(); drain(); guest.respondToRematch(true); drain()
            assertEquals(2, host.state.value.round); assertNull(host.state.value.resignedBy); assertNull(guest.state.value.resignedBy)
            host.resign(); drain()
            assertEquals(host.state.value.localPlayer, guest.state.value.resignedBy)
            host.close(); guest.close()
        }
    }

    @Test fun hostCannotForgeTheGuestsResignation() {
        val (_, guest, channel) = gomoku(1)
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(RoomControl.Resigned(1,1,2)))); drain()
        assertFalse(guest.state.value.connected)
        assertEquals(GomokuOutcome.PLAYING, guest.state.value.game.outcome)
        assertNull(guest.state.value.resignedBy)
    }

    @Test fun xiangqiUndoPresenceAndResignationShareTheSameSafeRoomSemantics() {
        val host = XiangqiLanSession(); val guest = XiangqiLanSession(); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = {1}
        host.host(); guest.join("192.168.1.8"); channel.connect(); host.respondToMatch(true); drain()
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5))); drain()
        guest.submitMove(XiangqiMove(GridCell(0,3),GridCell(0,4))); drain()
        host.requestUndo(); drain(); guest.respondToUndo(true); drain()
        assertEquals(XiangqiEngine.newGame(), host.state.value.game); assertEquals(host.state.value.game, guest.state.value.game)
        guest.setForeground(false); drain(); assertTrue(host.state.value.remoteBackground)
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5))); drain(); assertEquals(3, host.state.value.revision)
        guest.setForeground(true); drain(); host.resign(); drain()
        assertEquals(XiangqiOutcome.BLACK_WON, guest.state.value.game.outcome)
        assertEquals(XiangqiSide.RED, guest.state.value.resignedBy)
        assertFalse(guest.state.value.canUndo)
    }

    @Test fun gomokuApprovedUndoBufferedPastTwentyFiveSecondsSurvivesBackgroundAndReconnect() {
        for(background in listOf(true,false)) {
            val (host, guest, channel) = gomoku(1)
            host.submitMove(GridCell(7,7)); drain(); guest.submitMove(GridCell(8,8)); drain()
            guest.requestUndo(); drain()
            if(background) { guest.setForeground(false); drain() }
            else { channel.host.events.recovering(); channel.guest.events.recovering() }
            channel.host.hold = true
            host.respondToUndo(true); drain()
            val approval = channel.host.sent.last { it.startsWith("GO2|UNDO_STATE|") }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
            assertTrue(guest.state.value.connected); assertEquals(2, guest.state.value.pendingUndoRequest)
            if(background) guest.setForeground(true)
            channel.host.hold = false
            channel.host.send(approval); drain()
            channel.host.events.recovered(); channel.guest.events.recovered(); drain()
            assertTrue(host.state.value.connected); assertTrue(guest.state.value.connected)
            assertEquals(host.state.value.game, guest.state.value.game)
            assertEquals(1, guest.state.value.game.board.count { it != 0 })
            assertNull(guest.state.value.pendingUndoRequest)
            host.close(); guest.close()
        }
    }

    @Test fun xiangqiConsentAndBufferedApprovalAreNotDiscardedDuringAShortBackgroundTrip() {
        val host = XiangqiLanSession(); val guest = XiangqiLanSession(); val channel = Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={1}
        host.host();guest.join("192.168.1.8");channel.connect();host.respondToMatch(true);drain()
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5)));drain()
        guest.submitMove(XiangqiMove(GridCell(0,3),GridCell(0,4)));drain()
        host.requestUndo();drain();guest.setForeground(false);drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(XiangqiSide.RED,host.state.value.pendingUndoRequest)
        assertEquals(XiangqiSide.RED,guest.state.value.pendingUndoRequest)
        guest.setForeground(true);drain()
        channel.host.hold=true;guest.respondToUndo(true);drain()
        val approval=channel.host.sent.last {it.startsWith("XQ2|UNDO_STATE|")}
        channel.guest.events.recovering()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
        assertEquals(XiangqiSide.RED,guest.state.value.pendingUndoRequest)
        channel.host.hold=false;channel.host.send(approval);drain()
        assertTrue(guest.state.value.connected);assertEquals(XiangqiEngine.newGame(),guest.state.value.game)
        assertEquals(host.state.value.game,guest.state.value.game)
    }

    @Test fun staleRevisionResignationStillEndsTheSameLiveRoundAndDoesNotLeakIntoRematch() {
        val (host,guest,channel)=gomoku(1)
        channel.guest.hold=true;guest.resign()
        val resignation=channel.guest.sent.last {it.startsWith("GO2|ROOM|RESIGN|")}
        host.submitMove(GridCell(7,7));drain()
        assertEquals(1,guest.state.value.revision)
        channel.guest.hold=false;channel.guest.send(resignation);drain()
        assertEquals(GomokuOutcome.HUMAN_WON,guest.state.value.game.outcome)
        assertEquals(host.state.value.game,guest.state.value.game)
        assertEquals(2,guest.state.value.revision)
        host.requestRematch();drain();guest.respondToRematch(true);drain()
        channel.guest.send(resignation);drain()
        assertTrue(host.state.value.connected);assertTrue(guest.state.value.connected)
        assertEquals(2,guest.state.value.round);assertEquals(GomokuOutcome.PLAYING,guest.state.value.game.outcome)
        // The previous guest intent cannot authorize a fabricated new-round concession.
        channel.host.send(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(RoomControl.Resigned(2,4,1))));drain()
        assertFalse(guest.state.value.connected);assertEquals(GomokuOutcome.PLAYING,guest.state.value.game.outcome)
    }

    @Test fun xiangqiResignationAtAnOlderRevisionArrivesAfterTheHostsMoveWithoutGettingLost() {
        val host=XiangqiLanSession();val guest=XiangqiLanSession();val channel=Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={1}
        host.host();guest.join("192.168.1.8");channel.connect();host.respondToMatch(true);drain()
        channel.guest.hold=true;guest.resign()
        val resignation=channel.guest.sent.last {it.startsWith("XQ2|ROOM|RESIGN|")}
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5)));drain()
        channel.guest.hold=false;channel.guest.send(resignation);drain()
        assertTrue(guest.state.value.connected);assertEquals(XiangqiOutcome.RED_WON,guest.state.value.game.outcome)
        assertEquals(host.state.value.game,guest.state.value.game);assertEquals(XiangqiSide.BLACK,guest.state.value.resignedBy)
    }

    @Test fun bothGomokuPlayersReceiveTheOthersProfileAndKeepItAcrossRematch() {
        val host=GomokuLanSession();val guest=GomokuLanSession();val channel=Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={1}
        host.host(playerName="小叶",avatarId="leaf");guest.join("192.168.1.8",playerName="小猫",avatarId="cat")
        channel.connect();host.respondToMatch(true);drain()
        assertTrue(host.state.value.connected);assertTrue(guest.state.value.connected)
        assertEquals("小猫",host.state.value.remoteName);assertEquals("cat",host.state.value.remoteAvatarId)
        assertEquals("小叶",guest.state.value.remoteName);assertEquals("leaf",guest.state.value.remoteAvatarId)
        assertEquals("leaf",host.state.value.localAvatarId);assertEquals("cat",guest.state.value.localAvatarId)
        host.requestRematch();drain();guest.respondToRematch(true);drain()
        assertEquals(2,guest.state.value.round);assertEquals("小叶",guest.state.value.remoteName)
        assertEquals("cat",host.state.value.remoteAvatarId)
    }

    @Test fun bothXiangqiPlayersReceiveTheOthersProfileBeforePlaying() {
        val host=XiangqiLanSession();val guest=XiangqiLanSession();val channel=Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={2}
        host.host(playerName="月亮",avatarId="moon");guest.join("192.168.1.8",playerName="星星",avatarId="star")
        channel.connect();host.respondToMatch(true);drain()
        assertTrue(host.state.value.connected);assertTrue(guest.state.value.connected)
        assertEquals("星星",host.state.value.remoteName);assertEquals("star",host.state.value.remoteAvatarId)
        assertEquals("月亮",guest.state.value.remoteName);assertEquals("moon",guest.state.value.remoteAvatarId)
        assertEquals("moon",host.state.value.localAvatarId)
    }

    @Test fun deliberateDepartureAtAnOlderRevisionKeepsTheHostsLastBoardInBothGames() {
        val (goHost,goGuest,goChannel)=gomoku(1)
        goChannel.host.hold=true
        goHost.submitMove(GridCell(7,7));drain()
        val lastGoBoard=goHost.state.value.game
        goGuest.close();drain()
        assertTrue(goHost.state.value.peerLeft);assertTrue(goHost.state.value.roomEnded)
        assertFalse(goHost.state.value.connected);assertFalse(goHost.state.value.sessionActive)
        assertEquals(lastGoBoard,goHost.state.value.game)

        val host=XiangqiLanSession();val guest=XiangqiLanSession();val channel=Channel()
        host.wireFactory=channel.factory;guest.wireFactory=channel.factory;host.firstPlayer={1}
        host.host();guest.join("192.168.1.8");channel.connect();host.respondToMatch(true);drain()
        channel.host.hold=true
        host.submitMove(XiangqiMove(GridCell(0,6),GridCell(0,5)));drain()
        val lastChessBoard=host.state.value.game
        guest.close();drain()
        assertTrue(host.state.value.peerLeft);assertTrue(host.state.value.roomEnded)
        assertFalse(host.state.value.connected);assertEquals(lastChessBoard,host.state.value.game)
    }
}
