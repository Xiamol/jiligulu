package com.jiligulu.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, manifest=Config.NONE)
class GlobalGlassPrefsTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun targetsDefaultToThirtyAndHaveHonestNotFasterThanRequestedIntervals() {
        assertEquals(GlobalGlassFrameRate.FPS_30, GlobalGlassFrameRate.fromFps(null))
        assertEquals(GlobalGlassFrameRate.FPS_30, GlobalGlassFrameRate.fromFps(999))
        for(rate in GlobalGlassFrameRate.entries) {
            assertTrue(rate.intervalMillis*rate.fps>=1000)
            assertTrue(rate.intervalNanos>0)
        }
    }
    @Test fun aSavedSamplingTargetSurvivesOpeningThePreferenceFileAgain()=runBlocking {
        withTimeout(10_000) {
            val file=File(temporary.root,"glass.preferences_pb")
            val first=SupervisorJob()
            val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(first+Dispatchers.IO),produceFile={file})
            try {
                val prefs=GlobalGlassPrefs(store)
                assertEquals(GlobalGlassFrameRate.DEFAULT,prefs.frameRate.first())
                prefs.setFrameRate(GlobalGlassFrameRate.FPS_120)
            } finally { first.cancelAndJoin() }
            val second=SupervisorJob()
            val restored=PreferenceDataStoreFactory.create(scope=CoroutineScope(second+Dispatchers.IO),produceFile={file})
            try { assertEquals(GlobalGlassFrameRate.FPS_120,GlobalGlassPrefs(restored).frameRate.first()) }
            finally { second.cancelAndJoin() }
        }
    }
}
