package com.jiligulu.app.ui.littleworld

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h1000dp-port-mdpi")
class XiangqiAnimationGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun undoDuringTravelLeavesTheBoardReadyForAnotherMove() {
        val initial = XiangqiEngine.newGame()
        var game by mutableStateOf(initial)
        val moves = mutableListOf<XiangqiMove>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column {
                    SecretXiangqiGame(
                        state = game, mode = XiangqiPlayMode.CPU, paused = false,
                        boardWidth = 300.dp, thinkingClock = XiangqiThinkingClock.reset(game, 120),
                        lan = XiangqiLanUiState(), onMode = {},
                        onMove = { moves += it; game = XiangqiEngine.play(game, it) },
                        onToggle = {}, onRestart = {}, onHost = {}, onJoin = {},
                        onDisconnect = {}, onPuzzle = {}, canUndo = game != initial,
                        onUndo = { game = initial },
                    )
                }
            }
        }
        tapCell(GridCell(0, 6))
        tapCell(GridCell(0, 5))
        compose.runOnIdle { assertEquals(1, moves.size) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("悔棋").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(initial, game) }
        tapCell(GridCell(0, 6))
        tapCell(GridCell(0, 5))
        compose.runOnIdle { assertEquals("The cancelled animation must not leave input locked", 2, moves.size) }
    }

    @Test fun aRematchWithTheSameRestorationTokenWaitsForItsOwnFinishPresentation() {
        val initial = XiangqiPuzzles.all.first().position
        val finished = XiangqiEngine.play(initial, XiangqiPuzzles.all.first().solution)
        var game by mutableStateOf(initial)
        var round by mutableStateOf(1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column {
                    SecretXiangqiGame(
                        state = game, mode = XiangqiPlayMode.LAN, paused = false,
                        boardWidth = 300.dp, thinkingClock = XiangqiThinkingClock.reset(game, 120),
                        lan = XiangqiLanUiState(connected = true, localSide = XiangqiSide.RED, round = round),
                        onMode = {}, onMove = {}, onToggle = {}, onRestart = { game = initial; round++ },
                        onHost = {}, onJoin = {}, onDisconnect = {}, onPuzzle = {},
                    )
                }
            }
        }
        compose.runOnIdle { game = finished }
        awaitRematchQuestion()
        compose.onNodeWithTag("game-finish-watermark").assertDoesNotExist()
        compose.onNode(hasText("再来一局") and hasAnyAncestor(hasTestTag("game-rematch-question"))).performClick()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("game-rematch-question").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { assertEquals(initial, game) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("game-rematch-question").assertDoesNotExist()
        compose.runOnIdle { game = finished }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("game-rematch-question").assertDoesNotExist()
        awaitRematchQuestion()
        compose.onNodeWithTag("game-rematch-question").assertExists()
    }

    @Test fun restoringATerminalPositionDoesNotReplayTheWatermarkOrOpenAQuestion() {
        val puzzle = XiangqiPuzzles.all.first()
        val restored = XiangqiEngine.play(puzzle.position, puzzle.solution)
        compose.setContent {
            MaterialTheme {
                Column {
                    SecretXiangqiGame(
                        state = restored, mode = XiangqiPlayMode.CPU, paused = true,
                        boardWidth = 300.dp, thinkingClock = XiangqiThinkingClock.reset(restored, 120),
                        lan = XiangqiLanUiState(), onMode = {}, onMove = {}, onToggle = {},
                        onRestart = {}, onHost = {}, onJoin = {}, onDisconnect = {}, onPuzzle = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            compose.onNodeWithTag("game-finish-watermark").assertDoesNotExist()
            compose.onNodeWithTag("game-rematch-question").assertDoesNotExist()
        }
    }

    private fun awaitRematchQuestion() {
        // Animation frames use the Compose clock; coroutine delay also needs Android's looper.
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithTag("game-rematch-question").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapCell(cell: GridCell) {
        compose.onNodeWithTag("xiangqi-board").performTouchInput {
            val padding = width * .06f
            click(Offset(padding + cell.x * (width - 2 * padding) / 8,
                padding + cell.y * (height - 2 * padding) / 9))
        }
        compose.mainClock.advanceTimeBy(32)
    }
}
