package com.jiligulu.app.ui.add

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.repository.CategoryReclassification
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class PendingReclassificationDialogTest {
    @get:Rule val compose = createComposeRule()

    // The dialog owns a separate Android window/recomposer. Pump its frame as well as
    // Compose's clock after input; waitForIdle alone can leave its old semantics tree.
    private fun dialogFrame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        val androidTarget = SystemClock.uptimeMillis() + 32
        compose.mainClock.advanceTimeBy(32)
        val remaining = (androidTarget - SystemClock.uptimeMillis()).coerceAtLeast(0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(remaining))
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun billsWithoutSuggestionsRemainVisibleAndOnlyCheckedReadyBillsCanBeConfirmed() {
        val known = BillEntity(id = 1, amountFen = 1280, type = BillType.EXPENSE, categoryId = 9,
            detail = "镜头清洁", timestamp = 1_790_000_000_000)
        val unknown = known.copy(id = 2, detail = "没有建议的账单")
        var state by mutableStateOf(PendingReclassificationState(open = true, total = 2, bills = listOf(known, unknown)))
        var confirmed = emptySet<Long>()
        compose.setContent { MaterialTheme {
            PendingReclassificationDialog(state, onDismiss = {}, onConfirm = { confirmed = it })
        } }
        compose.mainClock.autoAdvance = false
        dialogFrame()
        compose.onNodeWithText("镜头清洁").assertIsDisplayed()
        compose.onNodeWithText("没有建议的账单").assertIsDisplayed()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(proposals = listOf(CategoryReclassification(known,
            AiBillDraft(category = "摄影", iconEmoji = "📷")))) }
        dialogFrame()
        compose.onNodeWithText("将创建：摄影").assertIsDisplayed()
        compose.onNodeWithTag("pending-select-1").assertIsOn()
        compose.onNodeWithTag("pending-select-1").performClick()
        dialogFrame()
        compose.onNodeWithTag("pending-select-1").assertIsOff()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        // A fresh proposal object for the same bill must not undo the manual cancellation.
        compose.runOnIdle { state = state.copy(proposals = state.proposals.map { it.copy(suggestion = it.suggestion.copy(keywords = "镜头")) }) }
        dialogFrame()
        compose.onNodeWithTag("pending-select-1").assertIsOff()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        // Compare the semantics action with real pointer delivery; neither is a fallback.
        compose.onNodeWithTag("pending-select-1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        dialogFrame()
        compose.onNodeWithTag("pending-select-1").assertIsOn()
        compose.onNodeWithTag("pending-select-1").performTouchInput { click() }
        dialogFrame()
        compose.onNodeWithTag("pending-select-1").assertIsOff()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        compose.onNodeWithTag("pending-select-1").performClick()
        dialogFrame()
        compose.onNodeWithTag("pending-select-1").assertIsOn()
        compose.onNodeWithText("确认 1 笔").performClick()
        dialogFrame()
        compose.runOnIdle { assertEquals(setOf(1L), confirmed) }
    }
}
