package com.jiligulu.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
class DisplayPerformancePrefsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun choiceSurvivesClosingAndReopeningTheActualPreferenceFile() = runBlocking {
        withTimeout(10_000) {
            val file = File(temporary.root, "display.preferences_pb")
            val firstJob = SupervisorJob()
            val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
            try {
                val prefs = DisplayPerformancePrefs(firstStore)
                assertEquals(AppRefreshRate.SYSTEM, prefs.refreshRate.first())
                prefs.setRefreshRate(AppRefreshRate.HZ_60)
                assertEquals(AppRefreshRate.HZ_60, prefs.refreshRate.first())
            } finally { firstJob.cancelAndJoin() }
            val secondJob = SupervisorJob()
            val secondStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file })
            try {
                val restored = DisplayPerformancePrefs(secondStore)
                assertEquals(AppRefreshRate.HZ_60, restored.refreshRate.first())
                restored.setRefreshRate(AppRefreshRate.SYSTEM)
                assertEquals(AppRefreshRate.SYSTEM, restored.refreshRate.first())
            } finally { secondJob.cancelAndJoin() }
        }
    }

    @Test fun unknownOlderOrCorruptRateFallsBackToSystem() = runBlocking {
        withTimeout(10_000) {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { File(temporary.root, "unknown.preferences_pb") })
            try {
                store.edit { it[intPreferencesKey("app_refresh_rate")] = -1 }
                assertEquals(AppRefreshRate.SYSTEM, DisplayPerformancePrefs(store).refreshRate.first())
            } finally { job.cancelAndJoin() }
        }
    }
}
