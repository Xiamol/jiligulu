package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GameFinishLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pauseAndResumeWithoutAnIntermediateFrameCannotFinishAnOldWatermark() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var fresh by mutableStateOf(0)
        var requested by mutableStateOf(0)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    GameFinishOverlay(GameFinishPresentation("你赢啦", "这一局结束了", FinishMood.WIN),
                        "round", "terminal", fresh, requested, {})
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { fresh++ }
        compose.waitUntil(timeoutMillis = 3_000) {
            pumpFrame()
            compose.onAllNodesWithTag("game-finish-watermark").fetchSemanticsNodes().isNotEmpty()
        }
        // No rendering or snapshot application between these lifecycle events: resumed ends true.
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        compose.onNodeWithTag("game-finish-watermark").assertDoesNotExist()
        compose.onNodeWithTag("game-rematch-question").assertDoesNotExist()

        // A deliberate new request is allowed after the interrupted automatic presentation.
        compose.runOnIdle { requested++ }
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("game-rematch-question").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pumpFrame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(32)
    }
}
