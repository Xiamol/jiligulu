package com.jiligulu.app.ui.littleworld

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAvatarExchangeTest {
    @Test fun anUncanonicalLocalPhotoFromANormalizerCannotBreakTheGameHandshake() {
        val sent = mutableListOf<List<String>>()
        val exchange = RoomAvatarExchange("AAA", true, false, false, sent::add, {}, { 100L }, { it })
        exchange.profileAccepted(); exchange.transportCapability()
        assertTrue(sent.isEmpty())
        assertFalse(exchange.receive("GO2|PING"))
    }
    private val photo = Base64.getEncoder().encodeToString(ByteArray(6144) { (it % 251).toByte() })
    private fun canonical(value: String): String = value.takeIf {
        it.length in 4..8192 && it.length % 4 == 0 && it.matches(Regex("[A-Za-z0-9+/]+={0,2}"))
    }.orEmpty()

    private inner class Fixture(online: Boolean = true, hosting: Boolean = false, advertised: Boolean = false,
        local: String = "", normalize: (String) -> String = ::canonical) {
        var now = 100L
        val sent = mutableListOf<List<String>>()
        val shown = mutableListOf<String>()
        val exchange = RoomAvatarExchange(local, online, hosting, advertised, sent::add, shown::add, { now }, normalize)
        fun acceptOnlineProfile() { exchange.profileAccepted(); exchange.transportCapability() }
        fun receive(lines: List<String>) { lines.forEach { assertTrue(exchange.receive(it)) } }
    }

    @Test fun oldOnlineAcksAndOldLanDiscoveryNeverCauseNewAvatarFrames() {
        val oldOnline = Fixture(local = photo)
        oldOnline.exchange.connected(); oldOnline.exchange.profileAccepted()
        assertTrue(oldOnline.sent.isEmpty())
        val oldLan = Fixture(online = false, advertised = false, local = photo)
        oldLan.exchange.connected(); oldLan.exchange.profileAccepted()
        assertTrue(oldLan.exchange.receive(RoomAvatarExchange.CAPABILITY))
        assertTrue(oldLan.sent.isEmpty())
        assertFalse(oldLan.exchange.receive("GO3|PING"))
        assertFalse(oldOnline.exchange.receive("XQ3|MOVE|8|0|0|0|1"))
    }

    @Test fun lanBothCapabilitiesAndValidatedProfilesAreRequiredBeforePhotosAndSendOnlyOnce() {
        val guest = Fixture(online = false, advertised = true, local = photo)
        val host = Fixture(online = false, hosting = true, local = photo)
        guest.exchange.connected()
        assertEquals(listOf(listOf(RoomAvatarExchange.CAPABILITY)), guest.sent)
        // Session wire ordering places a validated HELLO before the following capability frame.
        host.exchange.profileAccepted()
        host.receive(guest.sent.single())
        assertEquals(RoomAvatarExchange.CAPABILITY, host.sent.first().single())
        assertEquals(RoomAvatarExchange.frames(photo), host.sent.last())
        guest.exchange.profileAccepted()
        guest.receive(host.sent.first())
        assertEquals(RoomAvatarExchange.frames(photo), guest.sent.last())
        val hostCount = host.sent.size
        val guestCount = guest.sent.size
        host.exchange.profileAccepted(); host.receive(listOf(RoomAvatarExchange.CAPABILITY))
        guest.exchange.connected(); guest.receive(listOf(RoomAvatarExchange.CAPABILITY))
        assertEquals(hostCount, host.sent.size)
        assertEquals(guestCount, guest.sent.size)
    }

    @Test fun onlineCapabilityMayArriveBeforeHelloWithoutSendingOrLosingThePhoto() {
        val fixture = Fixture(local = photo)
        fixture.exchange.transportCapability()
        assertTrue(fixture.sent.isEmpty())
        fixture.exchange.profileAccepted()
        assertEquals(listOf(RoomAvatarExchange.frames(photo)), fixture.sent)
        fixture.exchange.transportCapability(); fixture.exchange.profileAccepted()
        assertEquals(1, fixture.sent.size)
    }

    @Test fun maximumPhotoUsesThirteenBoundedChunksAndAllFramesFitTheGameLineLimit() {
        assertEquals(8192, photo.length)
        val frames = RoomAvatarExchange.frames(photo)
        val chunks = frames.filter { it.startsWith("AV1|CHUNK|") }
        assertEquals(13, chunks.size)
        assertEquals(15, frames.size)
        assertTrue(chunks.all { it.substringAfterLast('|').length <= 640 })
        assertEquals(photo, chunks.joinToString("") { it.substringAfterLast('|') })
        assertTrue(frames.all { it.toByteArray(Charsets.US_ASCII).size <= 1024 })
        assertEquals((0..12).toList(), chunks.map { it.split('|')[3].toInt() })
    }

    @Test fun completedPhotoReplaysAndCapabilitiesNeverRepeatEffectiveCallbacks() {
        val fixture = Fixture()
        fixture.acceptOnlineProfile()
        val frames = RoomAvatarExchange.frames(photo)
        fixture.receive(frames)
        assertEquals(listOf(photo), fixture.shown)
        assertNull(fixture.exchange.deadline)
        fixture.exchange.transportCapability()
        fixture.receive(frames)
        fixture.receive(listOf(RoomAvatarExchange.CAPABILITY))
        assertEquals(listOf(photo), fixture.shown)
        assertFalse(fixture.exchange.receive("GO3|PING"))
    }

    @Test fun photosWithoutCapabilityOrValidatedHelloAreIgnoredAndDoNotAllocateATransfer() {
        val noCapability = Fixture()
        noCapability.exchange.profileAccepted()
        noCapability.receive(RoomAvatarExchange.frames(photo))
        val noHello = Fixture()
        noHello.exchange.transportCapability()
        noHello.receive(RoomAvatarExchange.frames(photo))
        for (fixture in listOf(noCapability, noHello)) {
            assertNull(fixture.exchange.deadline)
            assertTrue(fixture.shown.isEmpty())
            assertFalse(fixture.exchange.receive("GO3|PING"))
        }
    }

    @Test fun missingOutOfOrderAndWrongHashPhotosReleaseTheFlightWithoutConsumingGameFrames() {
        val frames = RoomAvatarExchange.frames(photo)
        val corruptedChunk = frames[1].replaceRange(frames[1].lastIndex, frames[1].lastIndex + 1,
            if (frames[1].last() == 'A') "B" else "A")
        val invalidTransfers = listOf(
            listOf(frames.first(), frames[2]),
            listOf(frames.first(), frames[1], frames.last()),
            listOf(frames.first(), corruptedChunk) + frames.drop(2),
            listOf(frames.first(), frames.first())
        )
        for (invalid in invalidTransfers) {
            val fixture = Fixture(); fixture.acceptOnlineProfile()
            fixture.receive(invalid)
            assertTrue(fixture.shown.isEmpty())
            assertNull(fixture.exchange.deadline)
            assertFalse(fixture.exchange.receive("GO3|PING"))
            fixture.receive(frames)
            assertTrue(fixture.shown.isEmpty())
        }
    }

    @Test fun outOfRangeLengthIndexChunkAndOversizeLinesFailOnlyTheOptionalChannel() {
        val frames = RoomAvatarExchange.frames(photo)
        val id = frames.first().split('|')[2]
        val invalidTransfers = listOf(
            listOf(frames.first().replace("|8192|", "|8196|")),
            listOf(frames.first().replace("|8192|", "|8191|")),
            listOf(frames.first(), "AV1|CHUNK|$id|13|AAAA"),
            listOf(frames.first(), "AV1|CHUNK|$id|-1|AAAA"),
            listOf(frames.first(), "AV1|CHUNK|$id|0|" + "A".repeat(641)),
            listOf(frames.first(), "AV1|CHUNK|$id|0|%%%%"),
            listOf("AV1|" + "A".repeat(1021))
        )
        for (invalid in invalidTransfers) {
            val fixture = Fixture(); fixture.acceptOnlineProfile()
            fixture.receive(invalid)
            assertTrue(fixture.shown.isEmpty())
            assertNull(fixture.exchange.deadline)
            assertFalse(fixture.exchange.receive("XQ3|PING"))
        }
    }

    @Test fun incompleteFlightExpiresAtFiveSecondsAndCannotBeRevivedByItsLateTail() {
        val fixture = Fixture(); fixture.acceptOnlineProfile()
        val frames = RoomAvatarExchange.frames(photo)
        fixture.receive(frames.take(2))
        assertEquals(5100L, fixture.exchange.deadline)
        fixture.now = 5099L; fixture.exchange.expire()
        assertEquals(5100L, fixture.exchange.deadline)
        fixture.now = 5100L; fixture.exchange.expire()
        assertNull(fixture.exchange.deadline)
        fixture.receive(frames.drop(2))
        assertTrue(fixture.shown.isEmpty())
        assertFalse(fixture.exchange.receive("GO3|PING"))
    }

    @Test fun theThirtyThirdAvatarPacketDisablesAndReleasesItsOnlyInFlightBuffer() {
        val fixture = Fixture(); fixture.acceptOnlineProfile()
        repeat(31) { fixture.receive(listOf(RoomAvatarExchange.CAPABILITY)) }
        fixture.receive(RoomAvatarExchange.frames(photo).take(1))
        assertEquals(5100L, fixture.exchange.deadline)
        fixture.receive(listOf(RoomAvatarExchange.CAPABILITY))
        assertNull(fixture.exchange.deadline)
        fixture.receive(RoomAvatarExchange.frames(photo).drop(1))
        assertTrue(fixture.shown.isEmpty())
        assertFalse(fixture.exchange.receive("GO3|PING"))
    }

    @Test fun normalizerFailuresKeepDefaultArtworkAndNeverFailTheGameChannel() {
        val badLocal = Fixture(local = photo, normalize = { error("decode failed") })
        badLocal.acceptOnlineProfile()
        assertTrue(badLocal.sent.isEmpty())
        val badPeer = Fixture(normalize = { error("decode failed") })
        badPeer.acceptOnlineProfile(); badPeer.receive(RoomAvatarExchange.frames(photo))
        assertTrue(badPeer.shown.isEmpty())
        assertNull(badPeer.exchange.deadline)
        assertFalse(badPeer.exchange.receive("GO3|PING"))
        val invalidPeer = Fixture(normalize = { "" })
        invalidPeer.acceptOnlineProfile(); invalidPeer.receive(RoomAvatarExchange.frames(photo))
        assertTrue(invalidPeer.shown.isEmpty())
        assertFalse(invalidPeer.exchange.receive("XQ3|PING"))
    }
}
