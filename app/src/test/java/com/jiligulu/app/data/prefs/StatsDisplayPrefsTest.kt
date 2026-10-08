package com.jiligulu.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class StatsDisplayPrefsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun changingModeEmitsImmediatelyAndSurvivesReopeningThePreferenceFile() = runBlocking {
        withTimeout(10_000) {
            val file = File(temporary.root, "stats.preferences_pb")
            val firstJob = SupervisorJob()
            val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
            try {
                val prefs = StatsDisplayPrefs(firstStore)
                assertEquals(StatsBarMode.MONTH_COMPRESSED, prefs.barMode.first())
                assertEquals(CalendarProgressMode.MONTH, prefs.calendarProgressMode.first())
                prefs.setCalendarProgressMode(CalendarProgressMode.YEAR)
                for (mode in StatsBarMode.entries) {
                    prefs.setBarMode(mode)
                    assertEquals(mode, prefs.barMode.first())
                }
            } finally { firstJob.cancelAndJoin() }
            val secondJob = SupervisorJob()
            val secondStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file })
            try {
                val prefs = StatsDisplayPrefs(secondStore)
                assertEquals(StatsBarMode.MONTH_COMPRESSED, prefs.barMode.first())
                assertEquals(CalendarProgressMode.YEAR, prefs.calendarProgressMode.first())
                prefs.setBarMode(StatsBarMode.COMPACT_TEN_DAYS)
                assertEquals(StatsBarMode.COMPACT_TEN_DAYS, prefs.barMode.first())
            } finally { secondJob.cancelAndJoin() }
        }
    }

    @Test fun unknownSettingDefaultsToWholeMonthAndExistingTenDayChoiceIsPreserved() = runBlocking {
        withTimeout(10_000) {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { File(temporary.root, "stats-unknown.preferences_pb") })
            try {
                store.edit { it[stringPreferencesKey("bar_mode")] = "future_unrecognized_mode" }
                assertEquals(StatsBarMode.MONTH_COMPRESSED, StatsDisplayPrefs(store).barMode.first())
                store.edit { it[stringPreferencesKey("bar_mode")] = "ten_days" }
                assertEquals(StatsBarMode.COMPACT_TEN_DAYS, StatsDisplayPrefs(store).barMode.first())
            } finally { job.cancelAndJoin() }
        }
    }
}
