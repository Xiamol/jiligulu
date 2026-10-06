package com.jiligulu.app.ui.littleworld

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h1000dp-port-mdpi")
class SnakeFinishGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aRunningSnakeEndingOpensTheRematchQuestion() {
        var state by mutableStateOf(SnakeEngine.newGame())
        var running by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column {
                    SecretSnakeGame(state, running, true, 240.dp, {}, {}, {})
                }
            }
        }
        compose.runOnIdle { running = true }
        compose.waitForIdle()
        compose.runOnIdle { state = state.copy(gameOver = true); running = false }
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithTag("game-rematch-question").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-finish-watermark").assertDoesNotExist()
    }

    @Test fun aRestoredFinishedSnakeDoesNotReplayItsEnding() {
        val restored = SnakeEngine.newGame().copy(gameOver = true)
        compose.setContent {
            MaterialTheme {
                Column { SecretSnakeGame(restored, false, true, 240.dp, {}, {}, {}) }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            compose.onNodeWithTag("game-finish-watermark").assertDoesNotExist()
            compose.onNodeWithTag("game-rematch-question").assertDoesNotExist()
        }
    }
}
