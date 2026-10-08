package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real sessions, HELLO authority, JPEG validation and timers; no socket, WebView or remote service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RoomAvatarSessionTest {
    private val handler = Handler(Looper.getMainLooper())
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun portraitsArrive() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    private fun photo(colour: Int): String {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        return try { ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)); Base64.getEncoder().encodeToString(output.toByteArray())
        } } finally { bitmap.recycle() }
    }
    private inner class Wire(val events: GomokuWireEvents) : GomokuRoomWire {
        lateinit var peer: Wire
        var closed = false
        var blockPortraits = false
        val sent = mutableListOf<String>()
        override fun send(line: String) {
            sent += line
            if (::peer.isInitialized && !closed) handler.post { if (!peer.closed) peer.events.data(line) }
        }
        override fun sendAvatar(line: String): Boolean {
            if (blockPortraits) return false
            send(line); return true
        }
        override fun close() { closed = true }
    }
    private inner class Channel {
        lateinit var host: Wire
        lateinit var guest: Wire
        val factory: (String, Boolean, GomokuWireEvents) -> GomokuRoomWire = { _, hosting, events ->
            Wire(events).also { if (hosting) host = it else guest = it }
        }
        fun connect(onlineProof: Boolean = false) {
            host.peer = guest; guest.peer = host
            handler.post(host.events.connected); handler.post(guest.events.connected)
            if (onlineProof) { handler.post(host.events.avatarCapable); handler.post(guest.events.avatarCapable) }
            drain()
        }
    }

    @Test fun onlineGomokuExchangesBothPhotosThenMakesAnOrdinaryLegalMove() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        val one = photo(Color.RED); val two = photo(Color.BLUE)
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("ABC123", "红头像", "cat", one); guest.join("ABC123", "蓝头像", "leaf", two)
            channel.connect(onlineProof = true); portraitsArrive()
            assertEquals(one, guest.state.value.remoteAvatarJpeg)
            assertEquals(two, host.state.value.remoteAvatarJpeg)
            assertEquals("cat", guest.state.value.remoteAvatarId)
            assertEquals("leaf", host.state.value.remoteAvatarId)
            host.submitMove(GridCell(7, 7)); drain()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(1, host.state.value.revision)
            assertEquals(host.state.value.game, guest.state.value.game)
            assertTrue((channel.host.sent + channel.guest.sent).all { it.length <= 1024 })
        } finally { host.close(); guest.close() }
    }

    @Test fun nearbyGomokuNegotiatesOnlyAfterTheHostsAdvertisedCapabilityAndKeepsIdentityMatching() {
        val host = GomokuLanSession(); val guest = GomokuLanSession(); val channel = Channel()
        val one = photo(Color.RED); val two = photo(Color.BLUE)
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host(playerName = "主人", avatarJpeg = one)
            host.allowNearbyMatching("111111111111")
            guest.joinNearby(NearbyGameRoom("111111111111", "主人", "192.168.1.8", 49762, avatarProtocol = 1),
                "222222222222", "来客", "moon", two)
            channel.connect(); portraitsArrive()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(one, guest.state.value.remoteAvatarJpeg)
            assertEquals(two, host.state.value.remoteAvatarJpeg)
            host.submitMove(GridCell(7, 7)); drain()
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun noCapabilityProofLeavesTheLegacyFiveFieldHelloAndGameplayUnchanged() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        val jpeg = photo(Color.GREEN)
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("OLD123", avatarId = "cat", avatarJpeg = jpeg); guest.join("OLD123", avatarId = "star", avatarJpeg = jpeg)
            channel.connect(); portraitsArrive()
            assertTrue((channel.host.sent + channel.guest.sent).none { it.startsWith(RoomAvatarExchange.PREFIX) })
            assertEquals("", host.state.value.remoteAvatarJpeg); assertEquals("", guest.state.value.remoteAvatarJpeg)
            val hello = channel.guest.sent.first { it.contains("|HELLO_NAME|") }.substringAfter("|ROOM|")
            assertEquals(5, hello.split('|').size)
            assertEquals("star", (RoomControlCodec.decode(hello) as RoomControl.Hello).avatarId)
            host.submitMove(GridCell(7, 7)); drain()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun malformedPortraitsDoNotDisconnectOrConsumeLegalChessPackets() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("BAD123"); guest.join("BAD123"); channel.connect(onlineProof = true)
            channel.guest.send("AV1|BEGIN|1234567890abcdef|8192|${"0".repeat(64)}")
            channel.guest.send("AV1|CHUNK|1234567890abcdef|2|AAAA"); drain()
            assertEquals("", host.state.value.remoteAvatarJpeg)
            host.submitMove(GridCell(7, 7)); drain()
            guest.submitMove(GridCell(8, 7)); drain()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(2, host.state.value.revision)
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun sessionExpiryDropsAnIncompletePhotoAndLateChunksWhileKeepingTheBoardPlayable() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("EXP123"); guest.join("EXP123"); channel.connect(onlineProof = true)
            val frames = RoomAvatarExchange.frames(photo(Color.BLUE))
            channel.guest.send(frames.first()); drain()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5100))
            frames.drop(1).forEach { channel.guest.send(it) }; drain()
            assertEquals("", host.state.value.remoteAvatarJpeg)
            host.submitMove(GridCell(7, 7)); drain()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(1, host.state.value.revision)
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun auxiliaryQueuePressureOnlyDropsThePortraitAndTheGameStillStartsAndMoves() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("BUS123", avatarJpeg = photo(Color.RED)); guest.join("BUS123", avatarJpeg = photo(Color.BLUE))
            channel.host.blockPortraits = true
            channel.connect(onlineProof = true); portraitsArrive()
            assertEquals("", guest.state.value.remoteAvatarJpeg)
            assertTrue(host.state.value.remoteAvatarJpeg.isNotEmpty())
            host.submitMove(GridCell(7, 7)); drain()
            assertEquals(1, host.state.value.revision)
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun onlineXiangqiSharesBothJpegsAndPreservesRedMoveAuthority() {
        val app = RuntimeEnvironment.getApplication()
        val host = XiangqiOnlineSession(app); val guest = XiangqiOnlineSession(app); val channel = Channel()
        val one = photo(Color.RED); val two = photo(Color.BLUE)
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory; host.firstPlayer = { 1 }
        try {
            host.host("XQA123", avatarJpeg = one); guest.join("XQA123", avatarJpeg = two)
            channel.connect(onlineProof = true); portraitsArrive()
            assertEquals(one, guest.state.value.remoteAvatarJpeg)
            assertEquals(two, host.state.value.remoteAvatarJpeg)
            host.submitMove(XiangqiMove(GridCell(0, 6), GridCell(0, 5))); drain()
            assertTrue(host.state.value.connected && guest.state.value.connected)
            assertEquals(1, host.state.value.revision)
            assertEquals(host.state.value.game, guest.state.value.game)
        } finally { host.close(); guest.close() }
    }

    @Test fun closingARoomCancelsRemainingPortraitTimersAndCannotSendIntoAnotherRoom() {
        val app = RuntimeEnvironment.getApplication()
        val host = GomokuOnlineSession(app); val guest = GomokuOnlineSession(app); val channel = Channel()
        host.wireFactory = channel.factory; guest.wireFactory = channel.factory
        host.host("END123", avatarJpeg = photo(Color.RED)); guest.join("END123", avatarJpeg = photo(Color.BLUE))
        channel.connect(onlineProof = true)
        host.close(); guest.close()
        val count = (channel.host.sent + channel.guest.sent).count { it.startsWith(RoomAvatarExchange.PREFIX) }
        portraitsArrive()
        assertEquals(count, (channel.host.sent + channel.guest.sent).count { it.startsWith(RoomAvatarExchange.PREFIX) })
        assertFalse(host.state.value.sessionActive); assertFalse(guest.state.value.sessionActive)
    }
}
