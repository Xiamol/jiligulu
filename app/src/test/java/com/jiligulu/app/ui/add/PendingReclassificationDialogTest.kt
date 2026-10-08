package com.jiligulu.app.ui.add

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class PendingReclassificationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun billsWithoutSuggestionsRemainVisibleAndOnlyCheckedReadyBillsCanBeConfirmed() {
        val known = BillEntity(id = 1, amountFen = 1280, type = BillType.EXPENSE, categoryId = 9,
            detail = "镜头清洁", timestamp = 1_790_000_000_000)
        val unknown = known.copy(id = 2, detail = "没有建议的账单")
        var state by mutableStateOf(PendingReclassificationState(open = true, total = 2, bills = listOf(known, unknown)))
        var confirmed = emptySet<Long>()
        compose.setContent { MaterialTheme {
            PendingReclassificationDialog(state, onDismiss = {}, onConfirm = { confirmed = it })
        } }
        compose.onNodeWithText("镜头清洁").assertIsDisplayed()
        compose.onNodeWithText("没有建议的账单").assertIsDisplayed()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(proposals = listOf(CategoryReclassification(known,
            AiBillDraft(category = "摄影", iconEmoji = "📷")))) }
        compose.onNodeWithText("将创建：摄影").assertIsDisplayed()
        compose.onNodeWithTag("pending-select-1").assertIsOn()
        compose.onNodeWithTag("pending-select-1").performClick()
        compose.onNodeWithTag("pending-select-1").assertIsOff()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        // A fresh proposal object for the same bill must not undo the manual cancellation.
        compose.runOnIdle { state = state.copy(proposals = state.proposals.map { it.copy(suggestion = it.suggestion.copy(keywords = "镜头")) }) }
        compose.onNodeWithTag("pending-select-1").assertIsOff()
        compose.onNodeWithText("确认 0 笔").assertIsNotEnabled()
        compose.onNodeWithTag("pending-select-1").performClick()
        compose.onNodeWithTag("pending-select-1").assertIsOn()
        compose.onNodeWithText("确认 1 笔").performClick()
        compose.runOnIdle { assertEquals(setOf(1L), confirmed) }
    }
}
