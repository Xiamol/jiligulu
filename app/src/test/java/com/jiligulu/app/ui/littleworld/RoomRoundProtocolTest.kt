package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class RoomRoundProtocolTest {
    @Test fun eitherFirstColorIsValidAndEveryAgreedRoundSwapsIt() {
        for (first in 1..2) {
            val round = RoomRoundRules.initial(first)
            assertTrue(RoomRoundRules.accepts(null, 0, round, false, false))
            val second = requireNotNull(RoomRoundRules.next(round, 17, true, true))
            assertEquals(RoomAssignment(2, 3 - first, 18), second)
            val third = requireNotNull(RoomRoundRules.next(second, 31, true, true))
            assertEquals(RoomAssignment(3, first, 32), third)
        }
    }

    @Test fun newBoardCannotReplaceExistingRoundWithoutBothVotesAndExactRevision() {
        val round = RoomRoundRules.initial(2)
        val next = RoomAssignment(2, 1, 8)
        assertNull(RoomRoundRules.next(round, 7, true, false))
        assertFalse(RoomRoundRules.accepts(round, 7, next, false, true))
        assertFalse(RoomRoundRules.accepts(round, 7, next, true, false))
        assertFalse(RoomRoundRules.accepts(round, 7, next.copy(hostPlayer = 2), true, true))
        assertFalse(RoomRoundRules.accepts(round, 7, next.copy(revision = 7), true, true))
        assertFalse(RoomRoundRules.accepts(round, 7, next.copy(round = 3), true, true))
        assertTrue(RoomRoundRules.accepts(round, 7, next, true, true))
    }

    @Test fun roundAndRevisionLimitsNeverWrap() {
        assertNull(RoomRoundRules.next(RoomAssignment(10_000, 1, 1), 12, true, true))
        assertNull(RoomRoundRules.next(RoomRoundRules.initial(1), Int.MAX_VALUE, true, true))
        assertFalse(RoomRoundRules.accepts(null, 0, RoomAssignment(1, 1, 1), true, true))
        assertFalse(RoomRoundRules.accepts(null, 0, RoomAssignment(2, 1, 0), true, true))
    }

    @Test fun nearbyAutoConsentRequiresBothLiveWaitingIdsAndSingleHostElection() {
        val host = "000000000001"
        val guest = "000000000002"
        val invitation = RoomControl.Hello("棋友", guest, host)
        assertTrue(RoomRoundRules.willingNearbyHost(host, invitation))
        assertFalse(RoomRoundRules.willingNearbyHost(null, invitation))
        assertFalse(RoomRoundRules.willingNearbyHost("000000000003", invitation))
        assertFalse(RoomRoundRules.willingNearbyHost(guest, RoomControl.Hello("棋友", host, guest)))
        assertFalse(RoomRoundRules.willingNearbyHost(host, RoomControl.Hello("棋友")))
        val candidates = listOf(NearbyGameRoom(guest, "乙", "192.168.1.8", 49761),
            NearbyGameRoom(host, "甲", "192.168.1.9", 49761))
        assertNull(NearbyRoomsState(candidates, localId = host).autoCandidate)
        assertEquals(host, NearbyRoomsState(candidates, localId = guest).autoCandidate?.id)
    }

    @Test fun typedControlsRoundTripThroughBothBoundedGameCodecs() {
        val messages = listOf(RoomControl.Hello("阿噜 ♡", "000000000002", "000000000001"),
            RoomControl.Start(RoomAssignment(2, 2, 67)), RoomControl.Vote(2, 67, true),
            RoomControl.Votes(2, 67, true, false), RoomControl.Close(2, 67, RoomCloseReason.RESULT_TIMEOUT),
            RoomControl.Presence(2,67,1,true),RoomControl.Presence(2,67,2,false),
            RoomControl.Resign(2,67,1),RoomControl.Resigned(2,68,1),
            RoomControl.DrawRequest(RoomDrawOffer(2,67,3,1)),RoomControl.DrawPending(RoomDrawOffer(2,67,3,1)),
            RoomControl.DrawResponse(RoomDrawOffer(2,67,3,1),true),
            RoomControl.DrawResult(RoomDrawOffer(2,67,3,1),RoomDrawResolution.ACCEPTED,68))
        for (message in messages) {
            assertEquals(XiangqiLanMessage.Control(message), XiangqiLanProtocol.decode(XiangqiLanProtocol.encode(XiangqiLanMessage.Control(message))))
            assertEquals(GomokuRoomMessage.Control(message), GomokuRoomProtocol.decode(GomokuRoomProtocol.encode(GomokuRoomMessage.Control(message))))
        }
    }

    @Test fun malformedInvitationOrUnboundedControlIsRejected() {
        for (bad in listOf("HELLO_NAME|_w|000000000002|000000000001", // Invalid UTF-8.
            "HELLO_NAME|5qOL5Y-L|000000000002|-", "HELLO_NAME|5qOL5Y-L|000000000001|000000000001",
            "START|0|1|0", "START|1|3|0", "VOTE|1|0|2", "VOTES|1|0|1|1|extra",
            "CLOSE|1|2147483648|LEFT", "PRESENCE|1|0|1|2", "PRESENCE|0|0|1|0",
            "RESIGN|1|0|3", "RESIGNED|1|0|1", "RESIGNED|1|1|2|extra",
            "DRAW_REQUEST|0|1|2|1","DRAW_PENDING|1|0|0|2","DRAW_RESPONSE|1|0|1|2|2",
            "DRAW_RESULT|1|0|1|2|ACCEPTED|2147483648","DRAW_RESULT|1|0|1|2|WIN|1")) {
            assertThrows(LanProtocolException::class.java) { RoomControlCodec.decode(bad) }
        }
    }

    @Test fun customCodesAreCanonicalAndNeverAdmitPeerIdSyntax() {
        assertEquals("ALU2026", RoomRoundRules.code(" alu2026 "))
        for (bad in listOf("abc", "ABCDEFGHIJKLM", "你好2026", "alu-code", "code|foo", "code/path"))
            assertNull(RoomRoundRules.code(bad))
        repeat(20) { assertEquals(RoomRoundRules.randomCode().length, 12) }
    }

    @Test fun ordinaryStateCannotSilentlyResetAGameNowThatRestartIsTyped() {
        val go = GomokuEngine.play(GomokuEngine.newGame(), 7, 7)
        assertFalse(GomokuRoomProtocol.acceptsSnapshot(1, go, true, GomokuRoomMessage.Snapshot(2, GomokuEngine.newGame()), allowRestart = false))
        val xq = XiangqiEngine.play(XiangqiEngine.newGame(), XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        assertFalse(XiangqiSnapshotRules.accepts(1, xq, true, XiangqiLanMessage.Snapshot(2, XiangqiEngine.newGame()), allowRestart = false))
        assertFalse(XiangqiSnapshotRules.accepts(1,xq,true,XiangqiLanMessage.Snapshot(2,xq.copy(outcome=XiangqiOutcome.DRAW)),allowRestart=false))
        assertFalse(GomokuRoomProtocol.acceptsSnapshot(1,go,true,GomokuRoomMessage.Snapshot(2,go.copy(outcome=GomokuOutcome.DRAW)),allowRestart=false))
    }

    @Test fun obsoleteRoomProtocolsAreAnExplicitVersionMismatch() {
        assertThrows(RoomVersionMismatchException::class.java){XiangqiLanProtocol.decode("XQ1|HELLO")}
        assertThrows(RoomVersionMismatchException::class.java){GomokuRoomProtocol.decode("GO1|HELLO")}
    }

    @Test fun aPartialAgreedDrawCannotBeImportedAsAnOrdinaryGomokuState() {
        val played=GomokuEngine.play(GomokuEngine.newGame(),7,7)
        val forged=GomokuRoomProtocol.encode(GomokuRoomMessage.Snapshot(2,played.copy(outcome=GomokuOutcome.DRAW)))
        assertThrows(LanProtocolException::class.java){GomokuRoomProtocol.decode(forged)}
    }

    @Test fun drawConsentCannotCrossNonceRoundRevisionOrBeForged() {
        val offer=RoomDrawOffer(2,7,4,2)
        val result=RoomControl.DrawResult(offer,RoomDrawResolution.ACCEPTED,8)
        assertTrue(RoomDrawRules.acceptsResult(2,7,offer,true,result))
        assertFalse(RoomDrawRules.acceptsResult(2,7,offer,false,result))
        assertFalse(RoomDrawRules.acceptsResult(3,7,offer,true,result))
        assertFalse(RoomDrawRules.acceptsResult(2,8,offer,true,result))
        assertFalse(RoomDrawRules.acceptsResult(2,7,offer,true,result.copy(offer=offer.copy(id=5))))
        assertFalse(RoomDrawRules.acceptsResult(2,7,offer,true,result.copy(resultingRevision=9)))
        assertFalse(RoomDrawRules.accepts(2,Int.MAX_VALUE,offer.copy(revision=Int.MAX_VALUE)))
    }

    @Test fun invitationProfilesAreOptionalButAvatarIdsMustComeFromTheBundledCatalogue() {
        assertEquals(RoomControl.Hello("棋友"), RoomControlCodec.decode("HELLO_NAME|5qOL5Y-L|-|-"))
        for (avatar in listOf("aru", "cat", "leaf", "moon", "star")) {
            val profile=RoomControl.Hello("棋友",avatarId=avatar)
            assertEquals(profile,RoomControlCodec.decode(RoomControlCodec.encode(profile)))
        }
        for (bad in listOf("https://example.test/a.png", "../cat", "CAT", "", "cat|extra")) {
            assertThrows(LanProtocolException::class.java) {
                RoomControlCodec.decode("HELLO_NAME|5qOL5Y-L|-|-|$bad")
            }
        }
    }
}
