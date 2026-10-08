package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.CompanionFact
import com.jiligulu.app.domain.persona.CompanionMemoryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CompanionHistoryLearningTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val oldTime = 1_800_000_000_000L - 7 * 86_400_000L
    private val revisionKey = longPreferencesKey("companion_memory_revision")
    private val versionKey = intPreferencesKey("companion_history_learning_version")
    private val factsKey = stringPreferencesKey("companion_memory_facts")
    private val enabledKey = booleanPreferencesKey("companion_memory_enabled")

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    // Use the production singleton instead of creating a competing DataStore for its file.
    // Raw companion keys are needed to represent pre-migration preferences precisely.
    @Suppress("UNCHECKED_CAST")
    private fun preferenceStore(): DataStore<Preferences> {
        val getter = Class.forName("com.jiligulu.app.data.prefs.UserPrefsKt")
            .getDeclaredMethod("getDataStore", Context::class.java)
        getter.isAccessible = true
        return getter.invoke(null, context) as DataStore<Preferences>
    }

    private suspend fun fixture(revision: Long? = null, version: Int? = null,
        existing: List<CompanionFact> = emptyList()): Fixture {
        preferenceStore().edit { prefs ->
            prefs.remove(revisionKey); prefs.remove(versionKey)
            prefs.remove(factsKey); prefs.remove(enabledKey)
            if (revision != null) prefs[revisionKey] = revision
            if (version != null) prefs[versionKey] = version
            if (existing.isNotEmpty()) prefs[factsKey] = CompanionMemoryPolicy.encode(existing)
        }
        return Fixture().also { it.prefs.setApiKeyOverride("fixture-not-a-real-key") }
    }

    private fun age(value: String = "19岁", time: Long = oldTime) =
        CompanionFact("age", "age", value, "我今年$value", time)

    private fun gender(time: Long = oldTime + 1_000L) =
        CompanionFact("gender", "gender", "男", "我是男生", time)

    private inner class Fixture {
        val queries = CopyOnWriteArrayList<String>()
        val clientCreations = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { queries += sqlQuery }
            }, Executor { it.run() })
            .build().also { opened += it }
        val prefs = UserPrefs(context)
        val history = ChatHistoryRepository(db)
        val ai = AiRepository(context, CategoryRepository(db.categoryDao(), db),
            BillRepository(db.billDao()), prefs, history, CategoryAdminRepository(db),
            synchronizeWaterReminder = { throw AssertionError("History learning must not change reminders") },
            clientFactory = {
                clientCreations.incrementAndGet()
                throw AssertionError("Local history learning must not create an HTTP client")
            })

        suspend fun add(kind: String, text: String, time: Long = oldTime,
            status: String = "", payload: String = ""): Long = history.insert(
            ChatMessageEntity(kind = kind, content = text, createdAt = time,
                status = status, draftPayload = payload))

        suspend fun addKnownHistory() {
            add("USER", "我是男生", oldTime)
            add("ASSISTANT", "你今年几岁呀？", oldTime + 1_000L)
            add("USER", "19", oldTime + 2_000L)
            add("ASSISTANT", "我还记住你在星河大学上大二了", oldTime + 3_000L)
            add("USER", "生日2月28", oldTime + 4_000L)
        }
    }

    @Test fun freshPreferencesLearnFactsAndMarkTheVersionInOneCompareAndSet() = runBlocking {
        val fixture = fixture()
        val before = fixture.prefs.companionMemory.first()
        assertEquals(0L, before.revision)
        assertEquals(0, before.historyLearningVersion)

        assertTrue(fixture.prefs.learnHistoricalFactsIfCurrent(before.revision, listOf(age(), gender())))
        val learned = fixture.prefs.companionMemory.first()
        assertEquals(before.revision + 1, learned.revision)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, learned.historyLearningVersion)
        assertEquals(setOf("age", "gender"), learned.facts.map { it.kind }.toSet())
        assertEquals(oldTime, learned.facts.single { it.kind == "age" }.updatedAt)
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(before.revision, listOf(age())))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(learned.revision, listOf(age())))
        assertEquals(learned, UserPrefs(context).companionMemory.first())
    }

    @Test fun evenAnEmptyScanIsMarkedCompleteAndDoesNotAdvanceAgain() = runBlocking {
        val fixture = fixture()
        fixture.ai.prepareLocalHistoryMemory()
        val afterEmptyScan = fixture.prefs.companionMemory.first()
        assertTrue(afterEmptyScan.facts.isEmpty())
        assertEquals(1L, afterEmptyScan.revision)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, afterEmptyScan.historyLearningVersion)

        fixture.add("USER", "我是男生")
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(afterEmptyScan, fixture.prefs.companionMemory.first())
        assertEquals(0, fixture.clientCreations.get())
    }

    @Test fun historicalUserTimesSurviveAutomaticLearningAndRepeatedPreparation() = runBlocking {
        val fixture = fixture()
        fixture.addKnownHistory()
        val candidates = fixture.ai.historicalMemoryCandidates().associateBy { it.kind }
        assertEquals(setOf("age", "gender", "birthday"), candidates.keys)
        assertEquals(oldTime, candidates.getValue("gender").updatedAt)
        assertEquals(oldTime + 2_000L, candidates.getValue("age").updatedAt)
        assertEquals("19", candidates.getValue("age").evidence)
        assertEquals(oldTime + 4_000L, candidates.getValue("birthday").updatedAt)

        fixture.ai.prepareLocalHistoryMemory()
        val learned = fixture.prefs.companionMemory.first()
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(learned, fixture.prefs.companionMemory.first())
        assertEquals(candidates, learned.facts.associateBy { it.kind })
        assertEquals(0, fixture.clientCreations.get())
    }

    @Test fun clearingAfterCandidateReadingRejectsTheOldSnapshotAndAnyNewAutomaticScan() = runBlocking {
        val fixture = fixture()
        fixture.addKnownHistory()
        val snapshot = fixture.prefs.companionMemory.first()
        val candidates = fixture.ai.historicalMemoryCandidates()
        fixture.prefs.clearCompanionMemories()
        val cleared = fixture.prefs.companionMemory.first()

        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(snapshot.revision, candidates))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(snapshot.revision, candidates, authorizedByUser = true))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(cleared.revision, candidates))
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(cleared, fixture.prefs.companionMemory.first())
        assertTrue(cleared.facts.isEmpty())
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, cleared.historyLearningVersion)
    }

    @Test fun disablingAndReenablingNeverSilentlyRestoresHistoricalFacts() = runBlocking {
        val fixture = fixture()
        fixture.addKnownHistory()
        val snapshot = fixture.prefs.companionMemory.first()
        val candidates = fixture.ai.historicalMemoryCandidates()
        fixture.prefs.setCompanionMemoryEnabled(false)
        val disabled = fixture.prefs.companionMemory.first()
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(disabled.revision, candidates, authorizedByUser = true))
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(disabled, fixture.prefs.companionMemory.first())

        fixture.prefs.setCompanionMemoryEnabled(true)
        val reenabled = fixture.prefs.companionMemory.first()
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(snapshot.revision, candidates, authorizedByUser = true))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(reenabled.revision, candidates))
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(reenabled, fixture.prefs.companionMemory.first())
        assertTrue(reenabled.facts.isEmpty())
        assertEquals(0, fixture.clientCreations.get())
    }

    @Test fun explicitRestorationAddsOnlyTheCandidatesTheUserSelected() = runBlocking {
        val fixture = fixture()
        fixture.addKnownHistory()
        val candidates = fixture.ai.historicalMemoryCandidates()
        fixture.prefs.clearCompanionMemories()
        val snapshot = fixture.prefs.companionMemory.first()
        val selected = candidates.filter { it.kind == "birthday" }
        assertEquals(1, selected.size)

        assertTrue(fixture.prefs.learnHistoricalFactsIfCurrent(snapshot.revision, selected, authorizedByUser = true))
        val restored = UserPrefs(context).companionMemory.first()
        assertEquals(selected, restored.facts)
        assertEquals("2月28日", restored.facts.single().value)
        assertEquals(oldTime + 4_000L, restored.facts.single().updatedAt)
        assertEquals(snapshot.revision + 1, restored.revision)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, restored.historyLearningVersion)
    }

    @Test fun deletingAndCorrectingFactsInvalidateCandidatesAndCloseAutomaticLearning() = runBlocking {
        val fixture = fixture()
        val initial = fixture.prefs.companionMemory.first()
        val candidates = listOf(age(), gender())
        assertTrue(fixture.prefs.learnHistoricalFactsIfCurrent(initial.revision, candidates))
        val beforeDelete = fixture.prefs.companionMemory.first()
        fixture.prefs.removeCompanionMemory("age")
        val deleted = fixture.prefs.companionMemory.first()
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(beforeDelete.revision, candidates, authorizedByUser = true))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(deleted.revision, candidates))
        assertEquals(listOf("gender"), deleted.facts.map { it.kind })

        fixture.prefs.correctCompanionMemory("gender", "女")
        val corrected = fixture.prefs.companionMemory.first()
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(deleted.revision, candidates, authorizedByUser = true))
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(corrected.revision, candidates))
        assertEquals("女", corrected.facts.single().value)
        assertTrue(corrected.facts.single().editedByUser)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, corrected.historyLearningVersion)
    }

    @Test fun legacyPreferencesWithARevisionButNoVersionRequireExplicitRestoration() = runBlocking {
        val fixture = fixture(revision = 7L)
        fixture.addKnownHistory()
        val legacy = fixture.prefs.companionMemory.first()
        assertEquals(7L, legacy.revision)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, legacy.historyLearningVersion)
        fixture.ai.prepareLocalHistoryMemory()
        assertEquals(legacy, fixture.prefs.companionMemory.first())

        val selected = fixture.ai.historicalMemoryCandidates().filter { it.kind == "age" }
        assertFalse(fixture.prefs.learnHistoricalFactsIfCurrent(legacy.revision, selected))
        assertTrue(fixture.prefs.learnHistoricalFactsIfCurrent(legacy.revision, selected, authorizedByUser = true))
        assertEquals(selected, fixture.prefs.companionMemory.first().facts)
        assertEquals(8L, fixture.prefs.companionMemory.first().revision)
        assertEquals(0, fixture.clientCreations.get())
    }

    @Test fun automaticLearningPreservesNewerAndUserEditedExistingFacts() = runBlocking {
        val edited = age("20岁", oldTime + 10_000L).copy(editedByUser = true, evidence = "")
        val currentGender = gender(oldTime + 20_000L).copy(value = "女", evidence = "我是女生")
        val fixture = fixture(revision = 0L, version = 0, existing = listOf(edited, currentGender))
        assertTrue(fixture.prefs.learnHistoricalFactsIfCurrent(0L, listOf(age(), gender())))

        val after = fixture.prefs.companionMemory.first()
        assertEquals(setOf(edited, currentGender), after.facts.toSet())
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, after.historyLearningVersion)
    }

    @Test fun personalHistoryPagesAreCappedOrderedAndUseExclusiveIdCursors() = runBlocking {
        val fixture = fixture()
        val ids = mutableListOf<Long>()
        for (index in 1..420) ids += fixture.add(if (index % 2 == 0) "ASSISTANT" else "USER",
            "普通对话$index", oldTime + index * 1_000L)
        fixture.add("DRAFT", "我是护士", payload = "我是护士".repeat(200_000))
        fixture.add("ASSISTANT", "我是程序员", status = "PENDING")
        fixture.add("ASSISTANT", "我是老师", status = "INTERRUPTED")
        fixture.add("USER", "")

        assertEquals(ids.take(128), fixture.history.personalMemoryAfter(limit = Int.MAX_VALUE).map { it.id })
        assertEquals(ids.takeLast(128), fixture.history.personalMemoryBefore(limit = Int.MAX_VALUE).map { it.id })
        assertEquals(ids.takeLast(8), fixture.history.personalMemoryBefore().map { it.id })
        assertEquals(ids.take(128), fixture.history.personalMemoryBefore(beforeId = ids[128], limit = 128).map { it.id })
        assertEquals(ids.drop(128).take(128), fixture.history.personalMemoryAfter(afterId = ids[127], limit = 128).map { it.id })
        val first = fixture.history.personalMemoryAfter(limit = 1).single()
        assertEquals("USER", first.role)
        assertEquals("普通对话1", first.text)
        assertEquals(oldTime + 1_000L, first.sentAt)
    }

    @Test fun candidatesUseOnlyTheHeadTailAndOneAdjacentQuestionWithoutReadingPayloads() = runBlocking {
        val fixture = fixture()
        for (index in 1..420) {
            val kind = when (index) { 1, 150, 294, 420 -> "USER"; else -> "ASSISTANT" }
            val text = when (index) {
                1 -> "我是男生"
                3 -> "我记住你是星河大学大二学生啦"
                150 -> "我喜欢骑车"
                293 -> "你今年几岁呀？"
                294 -> "19"
                420 -> "生日2月28"
                else -> "聊聊日常$index"
            }
            fixture.add(kind, text, oldTime + index * 1_000L)
        }
        fixture.add("DRAFT", "我是护士，我喜欢游泳", payload = "我是护士，我喜欢游泳".repeat(200_000))
        fixture.queries.clear()

        val candidates = fixture.ai.historicalMemoryCandidates().associateBy { it.kind }
        assertEquals(setOf("gender", "age", "birthday"), candidates.keys)
        assertEquals("男", candidates.getValue("gender").value)
        assertEquals("19岁", candidates.getValue("age").value)
        assertEquals(oldTime + 294_000L, candidates.getValue("age").updatedAt)
        assertEquals("19", candidates.getValue("age").evidence)
        assertEquals("2月28日", candidates.getValue("birthday").value)
        assertFalse(candidates.values.any { it.value in setOf("骑车", "游泳", "护士", "星河大学", "大二") })

        val reads = fixture.queries.filter { it.trimStart().startsWith("SELECT", true) &&
            it.contains("FROM chat_messages", true) }
        assertEquals(3, reads.size)
        assertTrue(reads.all { !it.contains("draftPayload", true) && !it.contains("rawInput", true) &&
            !Regex("SELECT\\s+\\*", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        assertEquals(0, fixture.clientCreations.get())
    }

    @Test fun aQuestionAtTheEndOfTheHeadCannotBindAnAnswerAcrossAnUnreadMiddle() = runBlocking {
        val fixture = fixture()
        for (index in 1..400) fixture.add(if (index == 273) "USER" else "ASSISTANT",
            when (index) {
                128 -> "你今年几岁呀？"
                273 -> "19"
                else -> "聊聊日常$index"
            }, oldTime + index * 1_000L)

        assertTrue(fixture.ai.historicalMemoryCandidates().isEmpty())
        assertEquals(0, fixture.clientCreations.get())
    }
}
