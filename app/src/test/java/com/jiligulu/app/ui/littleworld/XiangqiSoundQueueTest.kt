package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiSoundQueueTest {
    private val initial = XiangqiEngine.newGame()
    private val red = XiangqiEngine.play(initial, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
    private val black = XiangqiEngine.play(red, XiangqiMove(GridCell(0, 3), GridCell(0, 4)))

    @Test fun adjacentMovesOfTheSameMaterialKeepSeparateIdentitiesAndEachLandOnce() {
        val sounds = XiangqiSoundQueue()
        val first = requireNotNull(sounds.register(initial, red, 0))
        val second = requireNotNull(sounds.register(red, black, 420))
        assertFalse(first.captured); assertFalse(second.captured)
        assertNotEquals(first.id, second.id)
        assertEquals(first.epoch, second.epoch)
        // The first animation lands after the CPU has already committed the second move.
        assertEquals(first, sounds.consume(first.epoch, red, black))
        assertNull(sounds.consume(first.epoch, red, black))
        assertEquals(second, sounds.consume(second.epoch, black, black))
        assertNull(sounds.firstPending)
    }

    @Test fun aCancelledCallbackCannotConsumeTheSameMoveAfterUndoOrRestore() {
        val sounds = XiangqiSoundQueue()
        val old = requireNotNull(sounds.register(initial, red, 0))
        sounds.invalidate()
        val replayed = requireNotNull(sounds.register(initial, red, 1_000))
        assertNotEquals(old.epoch, replayed.epoch)
        assertNull(sounds.consume(old.epoch, red, red))
        assertEquals(replayed, sounds.consume(replayed.epoch, red, red))
    }

    @Test fun fallbackAndRealLandingShareTheSameSingleConsumption() {
        val sounds = XiangqiSoundQueue()
        val first = requireNotNull(sounds.register(initial, red, 100))
        val second = requireNotNull(sounds.register(red, black, 200))
        assertEquals(100L + XiangqiSoundQueue.FALLBACK_MILLIS, first.fallbackAtMillis)
        assertEquals(200L + XiangqiSoundQueue.FALLBACK_MILLIS * 2, second.fallbackAtMillis)
        assertEquals(first, sounds.consume(first.epoch, red, black))
        // A delayed fallback for this already-landed event cannot play it again.
        assertNull(sounds.consume(first.epoch, red, black))
        assertEquals(second, sounds.firstPending)
    }

    @Test fun callbacksCannotPlayAcrossAnUnobservedPositionChange() {
        val sounds = XiangqiSoundQueue()
        val event = requireNotNull(sounds.register(initial, red, 0))
        assertNull(sounds.consume(event.epoch, red, initial))
        sounds.invalidate()
        assertNull(sounds.consume(event.epoch, red, red))
    }

    @Test fun aSkippedAnimationDiscardsIntermediateSoundsAndRetainsOnlyTheLatestEvent() {
        val sounds = XiangqiSoundQueue()
        val first = requireNotNull(sounds.register(initial, red, 0))
        val second = requireNotNull(sounds.register(red, black, 420))
        val latest = requireNotNull(sounds.restartLatest(second.epoch, black, black, 500))
        assertNotEquals(second.epoch, latest.epoch)
        assertEquals(500 + XiangqiSoundQueue.FALLBACK_MILLIS, latest.fallbackAtMillis)
        assertNull(sounds.consume(first.epoch, red, black))
        assertNull(sounds.consume(second.epoch, black, black))
        assertEquals(latest, sounds.consume(latest.epoch, black, black))
        assertNull(sounds.firstPending)
    }

    @Test fun nonMoveChangesCancelTheOldChainWithoutCreatingImpactSounds() {
        val sounds = XiangqiSoundQueue()
        val event = requireNotNull(sounds.register(initial, red, 0))
        assertNull(sounds.register(red, initial, 100))
        assertNull(sounds.firstPending)
        assertNull(sounds.consume(event.epoch, red, red))
    }

    @Test fun anOverflowDropsTheOldPresentationAndKeepsOnlyTheLatestFallback() {
        val sounds = XiangqiSoundQueue()
        var position = initial
        var first: XiangqiSoundQueue.Event? = null
        var latest: XiangqiSoundQueue.Event? = null
        for (file in listOf(0, 2, 4)) {
            for (redTurn in listOf(true, false)) {
                val move = if (redTurn) XiangqiMove(GridCell(file, 6), GridCell(file, 5))
                    else XiangqiMove(GridCell(file, 3), GridCell(file, 4))
                val next = XiangqiEngine.play(position, move)
                latest = requireNotNull(sounds.register(position, next, 0))
                if (first == null) first = latest
                position = next
            }
        }
        val firstEvent = requireNotNull(first)
        val latestEvent = requireNotNull(latest)
        assertNotEquals(firstEvent.epoch, latestEvent.epoch)
        assertEquals(latestEvent, sounds.firstPending)
        assertNull(sounds.consume(firstEvent.epoch, firstEvent.position, position))
        assertEquals(latestEvent, sounds.consume(latestEvent.epoch, position, position))
        assertNull(sounds.firstPending)
    }
}
