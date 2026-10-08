package com.jiligulu.app.ui.littleworld

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ArtworkMemoryCacheTest {
    private data class Picture(val name: String, val bytes: Long)
    @Test fun byteBudgetEvictsTheLeastRecentlyUsedPictureRatherThanCountingEntries() {
        val cache = ArtworkMemoryCache<String, Picture>(12) { it.bytes }
        val room = cache.getOrLoad("room") { Picture("room", 6) }
        cache.getOrLoad("paper") { Picture("paper", 2) }
        cache.getOrLoad("stars") { Picture("stars", 2) }
        assertSame(room, cache.get("room"))
        cache.getOrLoad("night") { Picture("night", 6) }
        assertNull(cache.get("paper"))
        assertNull(cache.get("stars"))
        assertSame(room, cache.get("room"))
        assertEquals(12L, cache.bytes)
        cache.trimTo(6)
        assertEquals(6L, cache.bytes)
        assertSame(room, cache.get("room"))
    }

    @Test fun anOversizedPictureCanBeVisibleWithoutBeingPinnedInTheGlobalCache() {
        val cache = ArtworkMemoryCache<String, Picture>(12) { it.bytes }
        val visible = cache.getOrLoad("large") { Picture("large", 15) }
        assertEquals("large", visible.name)
        assertNull(cache.get("large"))
        assertEquals(0L, cache.bytes)
    }

    @Test fun concurrentColdRequestsDecodeOnePictureAndReuseItsIdentity() {
        val cache = ArtworkMemoryCache<String, Picture>(12) { it.bytes }
        val workers = Executors.newFixedThreadPool(6)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decodes = AtomicInteger()
        try {
            val requests = (1..6).map { workers.submit<Picture> { cache.getOrLoad("room") {
                decodes.incrementAndGet(); started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Picture("room", 6)
            } } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            val results = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, decodes.get())
            results.forEach { assertSame(results.first(), it) }
            assertEquals(6L, cache.bytes)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun backgroundTrimDoesNotWaitForDecodeOrAllowItsLateResultToRefillTheCache() {
        val cache = ArtworkMemoryCache<String, Picture>(12) { it.bytes }
        val workers = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val decode = workers.submit<Picture> { cache.getOrLoad("room") {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); Picture("room", 6)
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workers.submit { cache.clear() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals("room", decode.get(5, TimeUnit.SECONDS).name)
            assertNull(cache.get("room"))
            assertEquals(0L, cache.bytes)
            cache.getOrLoad("room") { Picture("room", 6) }
            assertEquals(6L, cache.bytes)
        } finally { release.countDown(); workers.shutdownNow() }
    }
}
