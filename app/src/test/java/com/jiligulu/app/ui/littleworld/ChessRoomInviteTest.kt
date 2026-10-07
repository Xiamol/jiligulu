package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class ChessRoomInviteTest {
    @Test fun inviteRoundTripsBothBoardGames() {
        listOf("xiangqi", "gomoku").forEach { kind ->
            val invite = ChessRoomInvite(kind, "ARU628")
            assertEquals(invite, ChessRoomInvite.parse(invite.uri))
            assertEquals(invite, ChessRoomInvite.parse(invite.webUri))
            assertEquals(invite, ChessRoomInvite.parse(invite.webUri.replace("/join/", "/join")))
        }
    }

    @Test fun webInvitesRejectForeignOrAmbiguousDestinations() {
        val base = "https://xiamol.github.io/jiligulu/join/"
        listOf(
            "http://xiamol.github.io/jiligulu/join/?game=gomoku&code=ROOM12",
            "https://xiamol.github.io.evil.test/jiligulu/join/?game=gomoku&code=ROOM12",
            "https://evil.test@xiamol.github.io/jiligulu/join/?game=gomoku&code=ROOM12",
            "https://xiamol.github.io:443/jiligulu/join/?game=gomoku&code=ROOM12",
            "https://xiamol.github.io/jiligulu/%6Aoin/?game=gomoku&code=ROOM12",
            "$base?game=gomoku&code=ROOM12&game=xiangqi",
            "$base?game=gomoku&code=ROOM12&extra=1",
            "$base?%67ame=gomoku&code=ROOM12",
            "$base?game=gomoku&code=ROOM12%26game%3Dxiangqi",
            "$base?game=gomoku&code=ROOM12#fragment",
        ).forEach { assertNull(it, ChessRoomInvite.parse(it)) }
    }

    @Test fun coldAndWarmLinksProduceTheSameCanonicalRoom() {
        val invite = ChessRoomInvite.parse("https://xiamol.github.io/jiligulu/join/?code=aru628&game=xiangqi")!!
        assertEquals(ChessRoomInvite("xiangqi", "ARU628"), invite)
        assertEquals(invite, ChessRoomInvite.parse(invite.uri))
        assertEquals(invite, ChessRoomInvite.parse(invite.webUri))
    }

    @Test fun invalidInvitationCannotChangeAnExistingRoom() {
        listOf(null, "https://chess/join?game=xiangqi&code=ROOM12", "jiligulu://other/join?game=xiangqi&code=ROOM12",
            "jiligulu://chess/join?game=snake&code=ROOM12", "jiligulu://chess/join?game=gomoku&code=12",
            "jiligulu://chess/join?game=gomoku&code=ROOM12&code=OTHER1", "jiligulu://chess/join?game=gomoku&code=ROOM12#x",
            "jiligulu://chess/join?game=gomoku&code=ROOM%20CODE").forEach { assertNull(it, ChessRoomInvite.parse(it)) }
    }

    @Test fun profileDiscardsControlCharactersAndUnrecognizedAvatar() {
        assertEquals(ChessPlayerProfile("小夏", "aru"), ChessPlayerProfile(" 小\n夏 ", "external-url").normalized())
        assertEquals(16, ChessPlayerProfile("夏".repeat(40), "cat").normalized().name.length)
        assertEquals("阿噜的朋友", ChessPlayerProfile(" ").normalized().name)
    }
}
