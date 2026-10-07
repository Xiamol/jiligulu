package com.jiligulu.app.ui.persona

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Disposal/cancellation must not need advancing a frozen animation clock to its end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GuluMascotLifetimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun removingAMascotDuringItsHelloDoesNotHoldTheCompositionOpen() {
        var shown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                if (shown) GuluMascot(Modifier.size(80.dp).testTag("mascot"), onClick = {})
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { shown = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("mascot").assertDoesNotExist()
    }

    @Test fun anOffscreenRetainedMascotStopsAnimatingWithoutWaitingForTheHello() {
        var active by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                GuluMascot(Modifier.size(80.dp).testTag("mascot"), onClick = {}, active = active)
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("mascot").assertIsDisplayed()
        val before = compose.mainClock.currentTime
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Off-screen idle cannot advance through another 4.7-second welcome",
            compose.mainClock.currentTime - before < 200)
    }
}
