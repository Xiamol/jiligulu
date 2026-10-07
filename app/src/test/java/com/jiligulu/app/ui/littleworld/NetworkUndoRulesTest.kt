package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class NetworkUndoRulesTest {
    private val initial = XiangqiEngine.newGame()
    private val red = XiangqiEngine.play(initial, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
    private val black = XiangqiEngine.play(red, XiangqiMove(GridCell(0, 3), GridCell(0, 4)))

    @Test fun hostCannotApproveItsOwnUndoAndGuestMustExplicitlyConsent() {
        val host = chessHistory(); val guest = chessHistory()
        val request = requireNotNull(host.beginLocal(2, black, XiangqiSide.RED))
        assertEquals(XiangqiUndoOffer.ACCEPTED, guest.receiveOffer(request, 2, black, XiangqiSide.BLACK, false))
        val next = XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(3, initial))
        assertNull(host.commitHost(request, XiangqiSide.RED, 2, black))
        assertFalse(guest.acceptsGuestUndo(XiangqiSide.BLACK, 2, black, next))
        assertFalse(guest.consentLocally(2, black, XiangqiSide.RED))
        assertTrue(guest.consentLocally(2, black, XiangqiSide.BLACK))
        assertEquals(initial, host.commitHost(request, XiangqiSide.BLACK, 2, black))
        assertTrue(guest.commitGuestUndo(XiangqiSide.BLACK, 2, black, next))
        assertEquals(XiangqiSide.RED, next.snapshot.game.turnSide)
        assertFalse(guest.commitGuestUndo(XiangqiSide.BLACK, 3, red, next))
    }

    @Test fun guestRequestedUndoNeedsHostApprovalAndOnlyKnownTargetIsAllowed() {
        val host = chessHistory(); val guest = chessHistory()
        val request = requireNotNull(guest.beginLocal(2, black, XiangqiSide.BLACK))
        assertEquals(XiangqiUndoOffer.ACCEPTED, host.receiveOffer(request, 2, black, XiangqiSide.RED, true))
        assertNull(host.commitHost(request, XiangqiSide.BLACK, 2, black))
        assertFalse(guest.acceptsGuestUndo(XiangqiSide.BLACK, 2, black,
            XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(3, initial))))
        assertFalse(guest.acceptsGuestUndo(XiangqiSide.BLACK, 2, black,
            XiangqiLanMessage.UndoSnapshot(request.copy(id = request.id + 1), XiangqiLanMessage.Snapshot(3, red))))
        assertEquals(red, host.commitHost(request, XiangqiSide.RED, 2, black))
        assertTrue(guest.commitGuestUndo(XiangqiSide.BLACK, 2, black,
            XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(3, red))))
        assertTrue(guest.canUndo)
    }

    @Test fun rejectionTimeoutCancellationAndRestartsDoNotChangeTheBoardOrAuthorizeReplays() {
        val history = chessHistory()
        val request = XiangqiUndoRequest(2, 4, XiangqiSide.BLACK)
        assertEquals(XiangqiUndoOffer.ACCEPTED, history.receiveOffer(request, 2, black, XiangqiSide.RED, true))
        assertEquals(XiangqiUndoOffer.DUPLICATE, history.receiveOffer(request, 2, black, XiangqiSide.RED, true))
        assertTrue(history.cancelIfMatches(request))
        assertNull(history.commitHost(request, XiangqiSide.RED, 2, black))
        assertEquals(XiangqiUndoOffer.STALE, history.receiveOffer(request, 2, black, XiangqiSide.RED, true))
        assertTrue(history.canUndo)
        assertTrue(history.recordAdvance(black, initial))
        assertFalse(history.canUndo)
        assertNull(history.beginLocal(3, initial, XiangqiSide.RED))
    }

    @Test fun staleWrongSideChangedBoardAndRevisionOverflowRequestsAreRejected() {
        val history = chessHistory()
        assertEquals(XiangqiUndoOffer.STALE, history.receiveOffer(XiangqiUndoRequest(1, 1, XiangqiSide.BLACK), 2, black, XiangqiSide.RED, true))
        assertEquals(XiangqiUndoOffer.STALE, history.receiveOffer(XiangqiUndoRequest(2, 1, XiangqiSide.RED), 2, black, XiangqiSide.RED, true))
        val request = requireNotNull(history.beginLocal(2, black, XiangqiSide.RED))
        assertNull(history.commitHost(request, XiangqiSide.BLACK, 3, black))
        assertNull(history.commitHost(request, XiangqiSide.BLACK, 2, red))
        history.cancelPending()
        assertNull(history.beginLocal(Int.MAX_VALUE, black, XiangqiSide.RED))
    }

    @Test fun simultaneousRequestsKeepTheHostOfferAndDoNotReuseTheLosingRequest() {
        val host = chessHistory(); val guest = chessHistory()
        val hostRequest = requireNotNull(host.beginLocal(2, black, XiangqiSide.RED))
        val guestRequest = requireNotNull(guest.beginLocal(2, black, XiangqiSide.BLACK))
        assertEquals(XiangqiUndoOffer.BUSY, host.receiveOffer(guestRequest, 2, black, XiangqiSide.RED, true))
        assertEquals(XiangqiUndoOffer.ACCEPTED, guest.receiveOffer(hostRequest, 2, black, XiangqiSide.BLACK, false))
        assertFalse(guest.cancelIfMatches(guestRequest))
        host.cancelPending()
        assertEquals(XiangqiUndoOffer.STALE, host.receiveOffer(guestRequest, 2, black, XiangqiSide.RED, true))
    }

    @Test fun anUndoCannotBeSmuggledAsAnOrdinarySnapshot() {
        val host = chessHistory(); val guest = chessHistory()
        val request = requireNotNull(host.beginLocal(2, black, XiangqiSide.RED))
        guest.receiveOffer(request, 2, black, XiangqiSide.BLACK, false)
        guest.consentLocally(2, black, XiangqiSide.BLACK)
        assertFalse(XiangqiSnapshotRules.accepts(2, black, true, XiangqiLanMessage.Snapshot(3, red)))
        assertTrue(XiangqiSnapshotRules.acceptsUndo(2, black, true, XiangqiSide.BLACK, guest,
            XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(3, initial))))
    }

    @Test fun allUndoPacketsRoundTripAndMalformedConsentsAreRejected() {
        val request = XiangqiUndoRequest(2, 4, XiangqiSide.RED)
        val packets = listOf(XiangqiLanMessage.UndoRequest(request), XiangqiLanMessage.UndoResponse(2, 4, XiangqiSide.RED, true),
            XiangqiLanMessage.UndoResponse(2, 4, XiangqiSide.RED, false), XiangqiLanMessage.UndoResult(request, XiangqiUndoResolution.TIMEOUT),
            XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(3, red)))
        for (packet in packets) {
            val wire = XiangqiLanProtocol.encode(packet)
            assertTrue(wire.length <= 1_024); assertEquals(packet, XiangqiLanProtocol.decode(wire))
        }
        for (wire in listOf("XQ2|UNDO_REQUEST|2|0|RED", "XQ2|UNDO_REQUEST|-1|2|RED", "XQ2|UNDO_REQUEST|2|1|UNKNOWN",
            "XQ2|UNDO_RESPONSE|2|1|2", "XQ2|UNDO_RESPONSE|2|1|RED|2", "XQ2|UNDO_RESPONSE|2|1|UNKNOWN|1",
            "XQ2|UNDO_RESPONSE|2|1|1|EXTRA", "XQ2|UNDO_RESULT|2|1|RED|YES")) rejects { XiangqiLanProtocol.decode(wire) }
    }

    @Test fun gomokuSnapshotsCheckCountsAndOneLegalStepAndKeepGameNamespacesSeparate() {
        val initial = GomokuEngine.newGame(); val one = GomokuEngine.play(initial, 7, 7); val two = GomokuEngine.play(one, 8, 8)
        for ((revision, game) in listOf(0 to initial, 1 to one, 2 to two)) {
            val packet = GomokuRoomMessage.Snapshot(revision, game)
            assertEquals(packet, GomokuRoomProtocol.decode(GomokuRoomProtocol.encode(packet)))
        }
        assertTrue(GomokuRoomProtocol.acceptsSnapshot(1, one, true, GomokuRoomMessage.Snapshot(2, two)))
        assertFalse(GomokuRoomProtocol.acceptsSnapshot(0, initial, false, GomokuRoomMessage.Snapshot(1, one)))
        assertFalse(GomokuRoomProtocol.acceptsSnapshot(1, one, true, GomokuRoomMessage.Snapshot(3, two)))
        rejects { GomokuRoomProtocol.decode(XiangqiLanProtocol.encode(XiangqiLanMessage.Hello)) }
        rejects { GomokuRoomProtocol.decode(GomokuRoomProtocol.encode(GomokuRoomMessage.Snapshot(1, one.copy(currentPlayer = 1)))) }
        rejects { GomokuRoomProtocol.decode("GO2|MOVE|1|225") }
        rejects { GomokuRoomProtocol.decode("GO2|STATE|1|2|PLAYING|0|" + List(225) { 2 }.joinToString(",")) }
    }

    @Test fun gomokuUndoAlsoRequiresOpponentConsentAndCannotRewriteOrReplay() {
        val initial = GomokuEngine.newGame(); val one = GomokuEngine.play(initial, 7, 7); val two = GomokuEngine.play(one, 8, 8)
        val host = GomokuUndoHistory(); val guest = GomokuUndoHistory()
        for (history in listOf(host, guest)) { assertTrue(history.record(initial, one)); assertTrue(history.record(one, two)) }
        val request = requireNotNull(host.begin(2, two, 1))
        assertEquals(XiangqiUndoOffer.ACCEPTED, guest.offer(request, 2, two, 2, false))
        val packet = GomokuRoomMessage.UndoSnapshot(request, GomokuRoomMessage.Snapshot(3, initial))
        assertEquals(packet, GomokuRoomProtocol.decode(GomokuRoomProtocol.encode(packet)))
        assertFalse(guest.acceptsGuest(2, 2, two, packet))
        assertNull(host.commitHost(request, 1, 2, two))
        assertTrue(guest.consent(2, two, 2))
        assertFalse(guest.acceptsGuest(2, 2, two, packet.copy(snapshot = GomokuRoomMessage.Snapshot(3, one))))
        assertEquals(initial, host.commitHost(request, 2, 2, two))
        assertTrue(guest.commitGuest(2, 2, two, packet))
        assertFalse(guest.commitGuest(2, 3, one, packet))
        assertEquals(1, packet.snapshot.game.currentPlayer)
        assertEquals(XiangqiUndoOffer.STALE, guest.offer(request, 3, one, 2, false))
    }

    @Test fun invalidHistoryStatesNeverBecomeUndoTargets() {
        val chess = XiangqiUndoHistory()
        assertFalse(chess.recordAdvance(initial, red.copy(board = red.board.toMutableList().apply { this[0] = 0 })))
        assertFalse(chess.canUndo)
        val go = GomokuUndoHistory(); val initialGo = GomokuEngine.newGame()
        assertFalse(go.record(initialGo, GomokuEngine.play(initialGo, 7, 7).copy(currentPlayer = 1)))
        assertFalse(go.canUndo)
    }

    private fun chessHistory() = XiangqiUndoHistory().also { assertTrue(it.recordAdvance(initial, red)); assertTrue(it.recordAdvance(red, black)) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Malformed packet was accepted") } catch (_: LanProtocolException) { } }
}
