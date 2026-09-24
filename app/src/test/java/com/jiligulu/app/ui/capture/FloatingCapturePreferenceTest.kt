package com.jiligulu.app.ui.capture

import android.app.Application
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class FloatingCapturePreferenceTest {
    @Test fun preferenceSurvivesNewOwnerAndExplicitOffIsRemembered() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val first = UserPrefs(context)
        first.setFloatingCaptureEnabled(true)
        val restored = UserPrefs(context)
        assertTrue(restored.floatingCaptureEnabled.first())
        restored.setFloatingCaptureEnabled(false)
        assertFalse(UserPrefs(context).floatingCaptureEnabled.first())
    }
}

