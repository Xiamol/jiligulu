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
class BillContextPrefsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectionEmitsAndSurvivesReopeningWhileUnknownValuesKeepTheThreeDayDefault() = runBlocking {
        withTimeout(10_000) {
            val file = File(temporary.root, "bill-context.preferences_pb")
            val firstJob = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
            try {
                val prefs = BillContextPrefs(store)
                assertEquals(BillContextWindow.THREE_DAYS, prefs.window.first())
                store.edit { it[stringPreferencesKey("window")] = "unknown" }
                assertEquals(BillContextWindow.THREE_DAYS, prefs.window.first())
                for (window in BillContextWindow.entries) {
                    prefs.setWindow(window)
                    assertEquals(window, prefs.window.first())
                }
            } finally { firstJob.cancelAndJoin() }
            val secondJob = SupervisorJob()
            val secondStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file })
            try { assertEquals(BillContextWindow.ALL, BillContextPrefs(secondStore).window.first()) }
            finally { secondJob.cancelAndJoin() }
        }
    }
}
