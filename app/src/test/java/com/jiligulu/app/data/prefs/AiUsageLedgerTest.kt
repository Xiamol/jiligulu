package com.jiligulu.app.data.prefs

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jiligulu.app.core.ai.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AiUsageLedgerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owners = mutableListOf<Job>()
    private val databases = mutableListOf<AiUsageDatabase>()
    private val day = LocalDate.of(2026, 10, 8)
    private var clock = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val custom = AiProviderProfile.custom().copy(name = "Fixture", address = "https://fixture.invalid/v1", model = "fixture-v1")
    private fun database(name: String = "cost-${UUID.randomUUID()}.db") = AiUsageDatabase(RuntimeEnvironment.getApplication(), name).also { databases += it }
    private fun repository(db: AiUsageDatabase): Pair<AiUsageRepository, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val owner = SupervisorJob().also { owners += it }
        val file = File(temporary.root, "${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO), produceFile = { file })
        return AiUsageRepository(store, db, { clock }) to store
    }
    @After fun close() = runBlocking { owners.forEach { it.cancelAndJoin() }; databases.forEach { it.close() } }

    @Test fun aStartedRequestKeepsItsOldPriceAndDuplicateObserverCannotCountItTwice() = runBlocking {
        val (repo, _) = repository(database())
        repo.setPrice(custom, AiPriceSnapshot.configured("0.02", "1", "4"))
        val old = repo.begin(custom, AiUsagePurpose.LEDGER_CHAT)
        repo.setPrice(custom, AiPriceSnapshot.configured("0.04", "2", "8"))
        repo.finish(old, AiTokenUsage(900, 100, 0, 80))
        repo.finish(old, AiTokenUsage(900, 100, 0, 80))
        val next = repo.begin(custom, AiUsagePurpose.LEDGER_CHAT)
        repo.finish(next, AiTokenUsage(900, 100, 0, 80))
        val total = repo.total().single()
        assertEquals(2L, total.calls)
        assertEquals(1_314_000_000L, total.knownPico!!)
        assertEquals(0L, total.unknownCalls)
        assertEquals("0.001314", picoYuanText(total.knownPico!!))
        assertNotEquals(old.price!!.version, next.price!!.version)
    }

    @Test fun suppliersModelsAndEndpointsNeverBorrowAnotherProfilesConfirmedPrice() = runBlocking {
        val (repo, _) = repository(database())
        assertEquals(AiPriceSnapshot.userDeepSeekDefault, repo.configuredPrice(AiProviderProfile.deepSeek()))
        assertNull(repo.configuredPrice(AiProviderProfile.deepSeek(AiConfig.DEEPSEEK_PRO_MODEL)))
        assertNull(repo.configuredPrice(custom))
        repo.setPrice(custom, AiPriceSnapshot.configured("0.02", "1", "4"))
        assertNull(repo.configuredPrice(custom.copy(model = "fixture-v2")))
        assertNull(repo.configuredPrice(custom.copy(address = "https://other-fixture.invalid/v1")))
        assertNotEquals(AiUsageTicket.providerGroup(custom), AiUsageTicket.providerGroup(custom.copy(address = "https://other-fixture.invalid/v1")))
        assertEquals(AiUsageTicket.providerKey(custom), AiUsageTicket.providerKey(custom.copy(name = "Renamed")))
    }

    @Test fun partialUsageKeepsOnlyKnownOutputAndNeverTurnsMissingCacheIntoAFullMiss() = runBlocking {
        val (repo, _) = repository(database())
        repo.setPrice(custom, AiPriceSnapshot.configured("0.02", "1", "4"))
        val usage = AiTokenUsage.fromResponse(Json.parseToJsonElement("""{"usage":{"prompt_tokens":1000,"completion_tokens":23}}""") as kotlinx.serialization.json.JsonObject)!!
        repo.finish(repo.begin(custom, AiUsagePurpose.HEART_LETTER), usage)
        val row = repo.details(day).single()
        assertEquals(92_000_000L, row.knownPico!!)
        assertNull(row.cachePico); assertNull(row.missPico)
        assertEquals(0L, row.cacheHitReportedCalls)
        assertEquals(1L, row.outputReportedCalls)
        assertEquals(1000L, row.unclassifiedInput)
        assertEquals(1L, row.unknownCalls)
    }

    @Test fun eachPurposeAndDayIsAggregatedWithoutLoadingTheEntireRequestHistory() = runBlocking {
        val (repo, _) = repository(database())
        val profile = AiProviderProfile.deepSeek()
        for (offset in 0..44) {
            clock = day.minusDays(offset.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            for (purpose in AiUsagePurpose.entries) repo.finish(repo.begin(profile, purpose), AiTokenUsage(10, 20, 0, 30))
        }
        val seven = repo.daily(day.minusDays(6), day)
        assertEquals(7 * AiUsagePurpose.entries.size, seven.size)
        assertEquals(7L * AiUsagePurpose.entries.size, seven.sumOf { it.calls })
        assertTrue(seven.all { LocalDate.parse(it.day) >= day.minusDays(6) && LocalDate.parse(it.day) <= day })
        assertEquals(45L * AiUsagePurpose.entries.size, repo.total().sumOf { it.calls })
        assertEquals(AiUsagePurpose.entries.toSet(), repo.total().map { it.purpose }.toSet())
    }

    @Test fun oldCountersMigrateOnceAsUnpricedUnknownPurposeAndPreserveDatedAndUndatedTotals() = runBlocking {
        val (repo, store) = repository(database())
        val snapshot = AiUsageSnapshot("2026-10-01", day.toString(),
            today = AiUsageTotals().add(AiTokenUsage(10, 20, 0, 30)),
            total = AiUsageTotals().add(AiTokenUsage(10, 20, 0, 30)).add(AiTokenUsage(40, 50, 0, 60)).add(null))
        store.edit { it[stringPreferencesKey("aggregate_v1")] = Json.encodeToString(AiUsageSnapshot.serializer(), snapshot) }
        val totals = repo.total().single()
        assertEquals(AiUsagePurpose.UNSPECIFIED, totals.purpose)
        assertEquals(3L, totals.calls)
        assertEquals(3L, totals.legacyCalls)
        assertNull(totals.knownPico)
        assertEquals(431_000_000L, totals.legacyEstimatePico)
        assertEquals(50L, totals.cacheHit)
        assertEquals(70L, totals.cacheMiss)
        assertEquals(90L, totals.output)
        assertEquals(1L, repo.daily(day, day).single().calls)
        assertEquals(140_200_000L, repo.daily(day, day).single().legacyEstimatePico)
        assertEquals(2L, repo.undatedCalls())
        assertEquals(1L, repo.daily(day.minusDays(30), day).sumOf { it.calls })
        repo.begin(AiProviderProfile.deepSeek(), AiUsagePurpose.LIU_REN)
        assertEquals(3L, repo.total().single().calls)
        assertEquals(2L, repo.undatedCalls())
        assertEquals(snapshot, repo.snapshots.first())
    }

    @Test fun undatedHistoryIsCountedSeparatelyAndRespectsProviderAndModelFilters() = runBlocking {
        val (repo, store) = repository(database())
        fun totals(calls: Int) = (0 until calls).fold(AiUsageTotals()) { total, _ -> total.add(AiTokenUsage(10, 20, 0, 30)) }
        val deepSeek = AiUsageSnapshot("2026-10-01", day.toString(), today = totals(1), total = totals(3))
        val customHistory = AiUsageSnapshot("2026-10-01", day.toString(), today = totals(2), total = totals(5))
        val customStorageKey = "provider_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("custom".toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        store.edit {
            it[stringPreferencesKey("aggregate_v1")] = Json.encodeToString(AiUsageSnapshot.serializer(), deepSeek)
            it[stringPreferencesKey(customStorageKey)] = Json.encodeToString(AiUsageSnapshot.serializer(), customHistory)
        }
        // Trigger migration through the new query itself, before any total/day query.
        assertEquals(5L, repo.undatedCalls())
        assertEquals(2L, repo.undatedCalls(providerGroup = "deepseek"))
        assertEquals(3L, repo.undatedCalls(providerGroup = "custom"))
        assertEquals(2L, repo.undatedCalls(providerKey = "legacy:deepseek"))
        assertEquals(3L, repo.undatedCalls(providerKey = "legacy:custom"))
        assertEquals(0L, repo.undatedCalls(providerGroup = "custom", providerKey = "legacy:deepseek"))
        assertEquals(0L, repo.undatedCalls(providerGroup = "unrelated-provider"))
        assertEquals(0L, repo.undatedCalls(providerKey = "unrelated-model"))
        assertEquals(3L, repo.daily(day.minusDays(30), day).sumOf { it.calls })
        assertEquals(8L, repo.total().sumOf { it.calls })
        assertTrue(repo.total().all { it.knownPico == null })
        // A newly dated request does not alter the undated history or rewrite v1 snapshots.
        repo.finish(repo.begin(AiProviderProfile.deepSeek(), AiUsagePurpose.LEDGER_CHAT), AiTokenUsage(10, 20, 0, 30))
        assertEquals(5L, repo.undatedCalls())
        assertEquals(9L, repo.total().sumOf { it.calls })
        assertEquals(deepSeek, repo.snapshots.first())
        assertEquals(customHistory, repo.snapshotsFor("custom").first())
    }

    @Test fun emptyAndOnlyDatedHistoriesHaveNoUndatedCalls() = runBlocking {
        val (repo, _) = repository(database())
        assertEquals(0L, repo.undatedCalls())
        repo.finish(repo.begin(AiProviderProfile.deepSeek(), AiUsagePurpose.LEDGER_CHAT), AiTokenUsage(10, 20, 0, 30))
        assertEquals(0L, repo.undatedCalls())
        assertEquals(1L, repo.daily(day, day).sumOf { it.calls })
    }

    @Test fun originalLegacyEstimateSurvivesNewPricesMixedRequestsAndDatabaseReopen() = runBlocking {
        val name = "legacy-price-${UUID.randomUUID()}.db"
        val first = database(name)
        val (repo, store) = repository(first)
        val today = AiUsageTotals().add(AiTokenUsage(10, 20, 30, 40))
        val snapshot = AiUsageSnapshot("2026-10-01", day.toString(), today = today,
            total = today.add(AiTokenUsage(50, 60, 70, 80)).add(null))
        store.edit { it[stringPreferencesKey("aggregate_v1")] = Json.encodeToString(AiUsageSnapshot.serializer(), snapshot) }
        val historical = repo.total().single()
        assertEquals(661_200_000L, historical.legacyEstimatePico)
        assertNull(historical.knownPico)
        assertEquals(3L, historical.unknownCalls)
        assertEquals(210_200_000L, repo.daily(day, day).single().legacyEstimatePico)
        assertEquals(2L, repo.undatedCalls())
        repo.setPrice(custom, AiPriceSnapshot.configured("100", "200", "300"))
        repo.finish(repo.begin(custom, AiUsagePurpose.CLASSIFICATION), AiTokenUsage(10, 20, 0, 40))
        val mixed = repo.total()
        assertEquals(historical, mixed.single { it.legacyCalls > 0 })
        assertEquals(17_000_000_000L, mixed.single { it.legacyCalls == 0L }.knownPico)
        assertNull(mixed.single { it.legacyCalls == 0L }.legacyEstimatePico)
        repo.setPrice(custom, AiPriceSnapshot.configured("500", "500", "500"))
        assertEquals(mixed, repo.total())
        assertEquals(snapshot, repo.snapshots.first())
        first.close()
        val (reopened, _) = repository(database(name))
        assertEquals(mixed, reopened.total())
        assertEquals(210_200_000L, reopened.daily(day, day).single { it.legacyCalls > 0 }.legacyEstimatePico)
        assertEquals(2L, reopened.undatedCalls())
    }

    @Test fun legacyUnknownOrOverflowIsNotInventedAsZeroAndReportedZeroStaysZero() = runBlocking {
        val cases = listOf(
            AiUsageTotals().add(null) to null,
            AiUsageTotals().add(AiTokenUsage(0, 0, 0, 0)) to 0L,
            AiUsageTotals().add(AiTokenUsage(Long.MAX_VALUE, 0, 0, 0)) to null
        )
        for ((totals, expected) in cases) {
            val (repo, store) = repository(database())
            val snapshot = AiUsageSnapshot("2026-10-01", day.toString(), today = totals, total = totals)
            store.edit { it[stringPreferencesKey("aggregate_v1")] = Json.encodeToString(AiUsageSnapshot.serializer(), snapshot) }
            val actual = repo.total().single()
            assertEquals(expected, actual.legacyEstimatePico)
            assertNull(actual.knownPico)
            assertEquals(1L, actual.unknownCalls)
            assertEquals(snapshot, repo.snapshots.first())
        }
    }

    @Test fun versionOneUpgradeRestoresOldEstimateWithoutChangingKnownCostsDatesOrPrices() = runBlocking {
        val name = "legacy-v1-${UUID.randomUUID()}.db"
        seedVersionOneDatabase(name)
        val upgraded = database(name)
        val (repo, _) = repository(upgraded)
        assertEquals(2, upgraded.readableDatabase.version)
        val total = repo.total().single()
        assertEquals(3L, total.calls)
        assertEquals(2L, total.legacyCalls)
        assertEquals(2L, total.unknownCalls)
        assertEquals(140_200_000L, total.legacyEstimatePico)
        assertEquals(123_456L, total.knownPico)
        assertEquals(1L, repo.undatedCalls())
        assertEquals(2L, repo.daily(day, day).sumOf { it.calls })
        val dated = repo.details(day)
        assertEquals(140_200_000L, dated.single { it.legacyCalls > 0 }.legacyEstimatePico)
        assertNull(dated.single { it.legacyCalls > 0 }.knownPico)
        assertNull(dated.single { it.legacyCalls == 0L }.legacyEstimatePico)
        assertEquals("v1-price", repo.configuredPrice(custom)?.version)
        assertEquals(7_000_000L, repo.configuredPrice(custom)?.cacheRateMicros)
        // Unknown-only historical calls retain NULL, rather than inventing a zero cost.
        upgraded.readableDatabase.rawQuery("SELECT legacyEstimatePico FROM attempts WHERE id='legacy:deepseek:undated'", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
        upgraded.close()
        val (reopened, _) = repository(database(name))
        assertEquals(total, reopened.total().single())
        assertEquals(1L, reopened.undatedCalls())
    }

    /** Actual v1 columns, including saved prices and the once-only legacy migration marker. */
    private fun seedVersionOneDatabase(name: String) {
        val context = RuntimeEnvironment.getApplication()
        val helper = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE prices (providerKey TEXT PRIMARY KEY, version TEXT NOT NULL, cacheRate INTEGER NOT NULL, missRate INTEGER NOT NULL, outputRate INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("""CREATE TABLE attempts (
                    id TEXT PRIMARY KEY, day TEXT, startedAt INTEGER NOT NULL, providerKey TEXT NOT NULL,
                    providerGroup TEXT NOT NULL, providerName TEXT NOT NULL, model TEXT NOT NULL, purpose TEXT NOT NULL,
                    calls INTEGER NOT NULL, reportedCalls INTEGER NOT NULL, inputReportedCalls INTEGER NOT NULL,
                    outputReportedCalls INTEGER NOT NULL, cacheReportedCalls INTEGER NOT NULL, cacheHitReportedCalls INTEGER NOT NULL,
                    cacheMissReportedCalls INTEGER NOT NULL, cacheHit INTEGER NOT NULL, cacheMiss INTEGER NOT NULL,
                    unclassifiedInput INTEGER NOT NULL, output INTEGER NOT NULL, cachePico INTEGER, missPico INTEGER,
                    outputPico INTEGER, flatPico INTEGER, knownPico INTEGER, unknownCalls INTEGER NOT NULL,
                    unknownFlags INTEGER NOT NULL, legacyCalls INTEGER NOT NULL, priceVersion TEXT,
                    cacheRate INTEGER, missRate INTEGER, outputRate INTEGER)""")
                db.execSQL("CREATE INDEX attempts_day ON attempts(day, purpose)")
                db.execSQL("CREATE INDEX attempts_provider_day ON attempts(providerKey, day)")
                db.execSQL("CREATE INDEX attempts_group ON attempts(providerGroup, purpose)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        try {
            val db = helper.writableDatabase
            db.insertOrThrow("metadata", null, ContentValues().apply { put("name", "legacy_v1"); put("value", "1") })
            db.insertOrThrow("prices", null, ContentValues().apply {
                put("providerKey", AiUsageTicket.providerKey(custom)); put("version", "v1-price")
                put("cacheRate", 7_000_000L); put("missRate", 8_000_000L); put("outputRate", 9_000_000L)
            })
            fun row(id: String, date: String?, legacy: Boolean, reported: Boolean) = ContentValues().apply {
                listOf("startedAt", "inputReportedCalls", "outputReportedCalls", "cacheReportedCalls", "cacheHitReportedCalls", "cacheMissReportedCalls", "unclassifiedInput").forEach { put(it, 0L) }
                put("id", id); put("day", date); put("providerKey", if (legacy) "legacy:deepseek" else "modern-fixture")
                put("providerGroup", "deepseek"); put("providerName", "Fixture"); put("model", "fixture-v1"); put("purpose", AiUsagePurpose.UNSPECIFIED.name)
                put("calls", 1L); put("reportedCalls", if (reported) 1L else 0L)
                put("cacheHit", if (reported) 10L else 0L); put("cacheMiss", if (reported) 20L else 0L); put("output", if (reported) 30L else 0L)
                put("unknownCalls", if (legacy) 1L else 0L); put("unknownFlags", if (legacy) AiCostUnknown.LEGACY else 0)
                put("legacyCalls", if (legacy) 1L else 0L)
                if (!legacy) { put("knownPico", 123_456L); put("outputPico", 123_456L) }
            }
            db.insertOrThrow("attempts", null, row("legacy:deepseek:dated", day.toString(), legacy = true, reported = true))
            db.insertOrThrow("attempts", null, row("legacy:deepseek:undated", null, legacy = true, reported = false))
            db.insertOrThrow("attempts", null, row("modern-fixture", day.toString(), legacy = false, reported = true))
        } finally { helper.close() }
    }

    @Test fun pricesAndFrozenCostsSurviveDatabaseReopenWithoutRepricingThePast() = runBlocking {
        val name = "reopen-${UUID.randomUUID()}.db"
        val first = database(name)
        val (repo, _) = repository(first)
        repo.setPrice(custom, AiPriceSnapshot.configured("0.02", "1", "4"))
        val ticket = repo.begin(custom, AiUsagePurpose.CLASSIFICATION)
        repo.finish(ticket, AiTokenUsage(10, 20, 0, 30))
        val before = repo.total().single()
        first.close()
        val (reopened, _) = repository(database(name))
        assertEquals(ticket.price, reopened.configuredPrice(custom))
        reopened.setPrice(custom, AiPriceSnapshot.configured("0.5", "2", "10"))
        assertEquals(before, reopened.total().single())
    }
}
