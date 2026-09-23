package com.jiligulu.app.data.announcement

import android.app.Application
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AnnouncementRepositoryTest {
    private val feed = """{"schemaVersion":1,"announcements":[{"id":"holiday","title":"一封来信","body":"节日快乐","endsAt":"2030-01-01T00:00:00Z"}]}"""
    private val prefs get() = UserPrefs(RuntimeEnvironment.getApplication())

    @Test fun closeOnlyLastsForTheProcessAndMuteOnlyAppliesToThatId() = runBlocking {
        val prefs = prefs
        prefs.setAnnouncementSource("https://example.test/feed.json")
        val first = AnnouncementRepository(prefs, { feed }, { 1L })
        first.initialize()
        assertEquals("holiday", first.state.value.automaticId)
        first.close()
        first.initialize()
        assertNull(first.state.value.automaticId)
        assertEquals(1, first.state.value.entries.size)
        val cold = AnnouncementRepository(prefs, { feed }, { 1L })
        cold.initialize()
        assertEquals("holiday", cold.state.value.automaticId)
        cold.muteOpened()
        val nextCold = AnnouncementRepository(prefs, { feed }, { 1L })
        nextCold.initialize()
        assertNull(nextCold.state.value.automaticId)
        nextCold.open("holiday")
        assertEquals("节日快乐", nextCold.state.value.opened!!.body)
        val newNotice = AnnouncementRepository(prefs, { feed.replace("holiday", "new-holiday") }, { 1L })
        newNotice.initialize()
        assertEquals("new-holiday", newNotice.state.value.automaticId)
    }

    @Test fun invalidOrOfflineFeedRetainsValidCacheButExpiresNormally() = runBlocking {
        val prefs = prefs
        val offlineFeed = feed.replace("holiday", "offline-notice")
        prefs.setAnnouncementSource("https://example.test/feed.json")
        AnnouncementRepository(prefs, { offlineFeed }, { 1L }).initialize()
        var now = 2L
        val offline = AnnouncementRepository(prefs, { error("offline") }, { now })
        offline.initialize()
        assertEquals(1, offline.state.value.entries.size)
        val bad = AnnouncementRepository(prefs, { "broken json" }, { now })
        bad.initialize()
        assertEquals("offline-notice", bad.state.value.automaticId)
        now = Announcement.instant("2030-01-01T00:00:00Z")
        offline.updateTime()
        assertTrue(offline.state.value.entries.isEmpty())
        assertNull(offline.state.value.opened)
    }

    @Test fun scheduledAndDisabledNoticesDoNotAutoPopupDuringAWarmSession() = runBlocking {
        val prefs = prefs
        prefs.setAnnouncementSource("https://example.test/feed.json")
        var now = Announcement.instant("2026-09-23T00:00:00Z")
        val scheduled = """{"announcements":[{"id":"future","title":"祝福","body":"你好","startsAt":"2026-09-24T08:00:00+08:00"},{"id":"off","title":"隐藏","body":"不显示","enabled":false}]}"""
        val repo = AnnouncementRepository(prefs, { scheduled }, { now })
        repo.initialize()
        assertTrue(repo.state.value.entries.isEmpty())
        now = Announcement.instant("2026-09-24T08:00:00+08:00")
        repo.updateTime()
        assertEquals(listOf("future"), repo.state.value.entries.map { it.id })
        assertNull(repo.state.value.automaticId)
        val cold = AnnouncementRepository(prefs, { scheduled }, { now })
        cold.initialize()
        assertEquals("future", cold.state.value.automaticId)
    }

    @Test fun emptyRemoteListWithdrawsCachedNoticesAndCancellationCanRetry() = runBlocking {
        val prefs = prefs
        prefs.setAnnouncementSource("https://example.test/feed.json")
        prefs.cacheAnnouncements(feed)
        var attempts = 0
        val repo = AnnouncementRepository(prefs, { if (++attempts == 1) throw CancellationException() else "{\"announcements\":[]}" }, { 1L })
        try { repo.initialize(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        repo.initialize()
        assertTrue(repo.state.value.entries.isEmpty())
        assertTrue(AnnouncementCodec.decode(prefs.readAnnouncements().cachedFeed).isEmpty())
    }

    @Test fun invalidDuplicateAndUnboundedAnnouncementsAreRejected() {
        val row = """{"id":"same","title":"标题","body":"内容"}"""
        for (raw in listOf("{\"announcements\":[$row,$row]}", feed.replace("2030-01-01T00:00:00Z", "not-a-date"),
            feed.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), " ".repeat(AnnouncementCodec.MAX_BYTES + 1))) {
            assertTrue(runCatching { AnnouncementCodec.decode(raw) }.isFailure)
        }
    }
}
