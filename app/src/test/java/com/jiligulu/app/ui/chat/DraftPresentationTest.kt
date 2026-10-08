package com.jiligulu.app.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class DraftPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun neitherExpandedNorCollapsedDraftRepeatsSourceOrTechnicalReceiptNotes() {
        val raw = "【图片记账】原图生成的大段原话"
        val metadata = "图片状态：支付成功，图中无相关账单时间，暂按系统时间，可修改"
        val card = ChatItem.DraftCard(id = 18, rawInput = raw,
            drafts = listOf(DraftUi(amountText = "3.5", detail = "奶茶", categoryName = "饮品", note = metadata)))
        var expanded by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            DraftCardView(card, emptyList(), onUpdate = { _, _ -> }, onConfirm = {}, onDelete = {},
                expanded = expanded, onExpandedChange = { expanded = it })
        } }
        compose.onNodeWithText("奶茶").assertIsDisplayed()
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithText("「$raw」").assertDoesNotExist()
        compose.onNodeWithText(metadata).assertDoesNotExist()
        compose.runOnIdle { expanded = false }
        compose.onNodeWithText("奶茶").assertIsDisplayed()
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithText("「$raw」").assertDoesNotExist()
    }
}
