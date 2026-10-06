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
    @Test fun globalRefractionDefaultsOffAndStoresOnlyUserPreference() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = UserPrefs(context)
        assertFalse(prefs.globalGlassRefractionEnabled.first())
        prefs.setGlobalGlassRefractionEnabled(true)
        assertTrue(UserPrefs(context).globalGlassRefractionEnabled.first())
        // A preference is not a MediaProjection grant and cannot make this service ready.
        assertFalse(ScreenCaptureService.ready.value)
        prefs.setGlobalGlassRefractionEnabled(false)
        assertFalse(UserPrefs(context).globalGlassRefractionEnabled.first())
    }
    @Test fun preferenceSurvivesNewOwnerAndExplicitOffIsRemembered() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val first = UserPrefs(context)
        first.setFloatingCaptureEnabled(true)
        val restored = UserPrefs(context)
        assertTrue(restored.floatingCaptureEnabled.first())
        restored.setFloatingCaptureEnabled(false)
        assertFalse(UserPrefs(context).floatingCaptureEnabled.first())
    }
    @Test fun iconSizeDefaultsToEightyPercentAndPersistsWithBounds() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = UserPrefs(context)
        assertEquals(80, prefs.floatingCaptureSizePercent.first())
        prefs.setFloatingCaptureSizePercent(120)
        assertEquals(120, UserPrefs(context).floatingCaptureSizePercent.first())
        prefs.setFloatingCaptureSizePercent(300)
        assertEquals(140, prefs.floatingCaptureSizePercent.first())
        prefs.setFloatingCaptureSizePercent(10)
        assertEquals(60, prefs.floatingCaptureSizePercent.first())
        prefs.setFloatingCaptureSizePercent(80)
        assertEquals(80, UserPrefs(context).floatingCaptureSizePercent.first())
    }
    @Test fun hideDropRestoresTheSettledPositionInsteadOfSavingTheTrashTarget() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = UserPrefs(context)
        val settled = com.jiligulu.app.data.prefs.FloatingCapturePosition(.87f, .35f)
        val trashTarget = com.jiligulu.app.data.prefs.FloatingCapturePosition(.5f, 1f)
        prefs.setFloatingCapturePosition(settled)
        prefs.setFloatingCapturePosition(FloatingCaptureGeometry.positionAfterDrop(settled, trashTarget, droppedToHide = true))
        assertEquals(settled, UserPrefs(context).readFloatingCapturePlacement().position)
        val next = com.jiligulu.app.data.prefs.FloatingCapturePosition(.12f, .76f)
        prefs.setFloatingCapturePosition(FloatingCaptureGeometry.positionAfterDrop(settled, next, droppedToHide = false))
        assertEquals(next, UserPrefs(context).readFloatingCapturePlacement().position)
    }

}

