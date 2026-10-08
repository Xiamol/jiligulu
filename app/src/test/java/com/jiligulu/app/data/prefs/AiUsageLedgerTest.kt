package com.jiligulu.app.data.prefs

import android.app.Application
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
        assertEquals(50L, totals.cacheHit)
        assertEquals(70L, totals.cacheMiss)
        assertEquals(90L, totals.output)
        assertEquals(1L, repo.daily(day, day).single().calls)
        repo.begin(AiProviderProfile.deepSeek(), AiUsagePurpose.LIU_REN)
        assertEquals(3L, repo.total().single().calls)
        assertEquals(snapshot, repo.snapshots.first())
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
