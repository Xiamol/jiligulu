package com.jiligulu.app.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jiligulu.app.ui.littleworld.SecretWoodDialog
import java.time.Duration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class PopupDismissalContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun genericCancelAliasesAreHiddenWhileBusyProtectsOutsideAndActualConfirmationStillRuns() {
        var shown by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var submits = 0
        compose.setContent { MaterialTheme {
            if (shown) GuluDialog("确认测试", { shown = false }, confirmLabel = "删除", dismissLabel = "先留着",
                busy = busy, onConfirm = { submits++; shown = false }) { Text("只有确认才执行") }
        } }
        compose.onNodeWithText("先留着").assertDoesNotExist()
        compose.onNodeWithText("删除").assertIsDisplayed()
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("正在处理…").assertIsNotEnabled()
        val protected = outside()
        compose.runOnIdle { assertTrue(shown); assertTrue(protected.isShowing); assertEquals(0, submits); busy = false }
        awaitDeleteReady()
        val closed = outside()
        awaitDismissed(closed)
        compose.runOnIdle { assertFalse(shown); assertEquals(0, submits); shown = true }
        awaitDeleteReady()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertFalse(shown); assertEquals(1, submits) }
    }

    @Test fun aRealGameDeclineAndAcceptBothRemainWhilePlainLaterIsOnlyAnOutsideDismiss() {
        var shown by mutableStateOf(true)
        var decision by mutableStateOf(true)
        var accepted = 0
        var declined = 0
        var plainClosures = 0
        compose.setContent { MaterialTheme {
            if (shown) SecretWoodDialog("棋局选择", onDismiss = { if (decision) declined++ else plainClosures++; shown = false },
                confirmLabel = "同意", onConfirm = { accepted++; shown = false },
                dismissLabel = if (decision) "继续下" else "稍后") { Text("保留实际选择") }
        } }
        compose.onNodeWithText("同意").assertIsDisplayed()
        compose.onNodeWithText("继续下").performClick()
        compose.runOnIdle { assertEquals(1, declined); assertEquals(0, accepted); shown = true }
        compose.onNodeWithText("同意").performClick()
        compose.runOnIdle { assertEquals(1, accepted); shown = true; decision = false }
        compose.onNodeWithText("稍后").assertDoesNotExist()
        val closed = outside()
        awaitDismissed(closed)
        compose.runOnIdle { assertFalse(shown); assertEquals(1, declined); assertEquals(1, accepted); assertEquals(1, plainClosures) }
    }

    private fun outside(): android.app.Dialog = compose.runOnIdle {
        val dialog = checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try { dialog.onTouchEvent(event) } finally { event.recycle() }
        dialog
    }
    private fun awaitDismissed(dialog: android.app.Dialog) {
        compose.waitUntil(8_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            !dialog.isShowing
        }
        compose.waitForIdle()
    }
    private fun awaitDeleteReady() {
        compose.waitUntil(8_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            ShadowDialog.getShownDialogs().any { it.isShowing } && runCatching {
                compose.onNodeWithText("删除").assertIsDisplayed().assertIsEnabled()
            }.isSuccess
        }
        // The new window must complete a layout/input frame after reopening, not reuse a
        // semantics action while the previous disabled dialog is being disposed.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
