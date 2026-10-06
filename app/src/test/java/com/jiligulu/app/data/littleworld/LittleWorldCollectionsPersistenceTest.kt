package com.jiligulu.app.data.littleworld

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production JSON and real Preferences protobuf files, with each store genuinely closed/reopened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LittleWorldCollectionsPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val opened = mutableListOf<Handle>()
    private val stateKey = stringPreferencesKey("state_v1")

    private data class Handle(val store: DataStore<Preferences>, val owner: CompletableJob, val repo: LittleWorldRepository)
    private fun open(file: File = File(temporary.root, "little_world.preferences_pb")): Handle {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        return Handle(store, job, LittleWorldRepository(RuntimeEnvironment.getApplication(), store)).also { opened += it }
    }
    @After fun close() = runBlocking { withTimeout(5_000) { opened.forEach { it.owner.cancelAndJoin() } } }

    @Test fun oldStateV1KeepsExistingCollectionsWhileNewFieldsDefaultAndPersist() = runBlocking {
        withTimeout(10_000) {
            val handle = open()
            handle.store.edit { it[stateKey] = """{
              "stickers":[{"id":"old-sticker","title":"公交","amountFen":200}],
              "wishes":[{"id":"old-wish","title":"旧愿望","targetFen":900,"createdAt":11,
                "deposits":[{"id":"old-deposit","amountFen":50,"createdAt":12}]}],
              "waiting":[{"id":"old-waiting","title":"想买的书","createdAt":13}],
              "futureNotes":[{"id":"old-note","title":"未来","body":"好好吃饭","dueAt":1000,"createdAt":14}],
              "cards":[{"id":"old-card","title":"旧照片","imagePath":"/owned/photo.jpg","createdAt":15}],
              "favoriteFortunes":[2,5],"timeMachineEnabled":false,"obsoleteDecoration":"safe to ignore"
            }""" }
            val before = handle.repo.snapshot()
            assertTrue(before.favoriteSecretPapers.isEmpty())
            assertTrue(before.writtenSecretPapers.isEmpty())
            assertTrue(before.trainTickets.isEmpty())
            assertEquals(50L, before.wishes.single().savedFen)
            assertEquals(setOf(2, 5), before.favoriteFortunes)
            assertFalse(before.timeMachineEnabled)

            val paper = SecretPaper(id = "new-paper", title = "给阿噜", body = "今天有一朵云", createdAt = 20)
            handle.repo.saveSecretPaper(paper)
            handle.repo.toggleSecretPaper(paper)
            handle.repo.saveTrainTicket(LocalDate.now().minusDays(1).toEpochDay())
            handle.owner.cancelAndJoin()
            val after = open().repo.snapshot()
            assertEquals(before, after.copy(favoriteSecretPapers = emptyList(), writtenSecretPapers = emptyList(), trainTickets = emptyList()))
            assertEquals(paper, after.writtenSecretPapers.single())
            assertEquals(paper, after.favoriteSecretPapers.single())
            assertEquals(1, after.trainTickets.size)
        }
    }

    @Test fun favoritesAreContentSnapshotsAndEditingDeduplicatesOnlyTheWrittenPaper() = runBlocking {
        withTimeout(10_000) {
            val handle = open()
            val original = SecretPaper(id = "snapshot-paper", title = "原来的话", body = "原来的心情", createdAt = 100)
            val revised = original.copy(title = "  后来的话  ", body = "  后来的心情  ")
            handle.repo.saveSecretPaper(original)
            handle.repo.toggleSecretPaper(original)
            coroutineScope { (1..12).map { async { handle.repo.saveSecretPaper(revised) } }.awaitAll() }
            val saved = handle.repo.snapshot()
            assertEquals(original, saved.favoriteSecretPapers.single())
            assertEquals(revised.copy(title = "后来的话", body = "后来的心情"), saved.writtenSecretPapers.single())
            handle.owner.cancelAndJoin()
            val reloaded = open().repo.snapshot()
            assertEquals(saved, reloaded)
        }
    }

    @Test fun concurrentBookmarkTogglesRemainUniqueAndDeletingOnePaperLeavesOtherCollections() = runBlocking {
        withTimeout(10_000) {
            val handle = open()
            val paper = SecretPaper(id = "delete-me", title = "一张纸条", body = "你好，阿噜", createdAt = 1)
            val other = SecretPaper(id = "keep-me", title = "另一张", body = "会保留的话", createdAt = 2)
            handle.repo.saveSecretPaper(paper)
            handle.repo.saveSecretPaper(other)
            handle.repo.toggleSecretPaper(other)
            handle.repo.saveTrainTicket(LocalDate.now().toEpochDay())
            coroutineScope { (1..21).map { async { handle.repo.toggleSecretPaper(paper) } }.awaitAll() }
            assertEquals(1, handle.repo.snapshot().favoriteSecretPapers.count { it.id == paper.id })
            val before = handle.repo.snapshot()
            handle.repo.deleteSecretPaper(paper.id)
            val after = handle.repo.snapshot()
            assertEquals(listOf(other), after.writtenSecretPapers)
            assertEquals(listOf(other), after.favoriteSecretPapers)
            assertEquals(before.copy(writtenSecretPapers = listOf(other), favoriteSecretPapers = listOf(other)), after)
        }
    }

    @Test fun invalidPapersCannotPartiallyMutateSavedCollections() = runBlocking {
        withTimeout(10_000) {
            val repo = open().repo
            val valid = SecretPaper(id = "valid", title = "完整的小纸条", body = "一段话", createdAt = 1)
            repo.saveSecretPaper(valid)
            val before = repo.snapshot()
            listOf(valid.copy(id = " "), valid.copy(title = " "), valid.copy(title = "题".repeat(41)),
                valid.copy(body = " "), valid.copy(body = "字".repeat(1501))).forEach { malformed ->
                assertTrue(runCatching { repo.saveSecretPaper(malformed) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(runCatching { repo.toggleSecretPaper(valid.copy(body = " ")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, repo.snapshot())
        }
    }

    @Test fun ticketsDeduplicateConcurrentlyKeepIssueTimeAndSurviveRealStoreRestart() = runBlocking {
        withTimeout(10_000) {
            val file = File(temporary.root, "tickets.preferences_pb")
            val handle = open(file)
            val today = LocalDate.now().toEpochDay()
            val first = LocalDate.of(1900, 1, 1).toEpochDay()
            handle.repo.saveTrainTicket(today)
            val issued = handle.repo.snapshot().trainTickets.single()
            coroutineScope { (1..20).map { n -> async { handle.repo.saveTrainTicket(if (n % 2 == 0) today else first) } }.awaitAll() }
            val saved = handle.repo.snapshot()
            assertEquals(setOf(today, first), saved.trainTickets.map { it.dayEpoch }.toSet())
            assertEquals(2, saved.trainTickets.size)
            assertEquals(issued, saved.trainTickets.single { it.dayEpoch == today })
            listOf(today + 1, first - 1, Long.MIN_VALUE, Long.MAX_VALUE).forEach { invalid ->
                assertTrue(runCatching { handle.repo.saveTrainTicket(invalid) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(saved, handle.repo.snapshot())
            assertTrue(file.length() > 0L)
            handle.owner.cancelAndJoin()
            val fresh = open(file)
            assertEquals(saved, fresh.repo.snapshot())
            assertNotEquals("A genuinely new DataStore reads the persisted protobuf", handle.store, fresh.store)
            assertNotNull(fresh.store.data.first()[stateKey])
        }
    }
}
