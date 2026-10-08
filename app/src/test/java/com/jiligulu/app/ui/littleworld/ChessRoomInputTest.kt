package com.jiligulu.app.ui.littleworld

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ChessRoomInputTest {
    @get:Rule val compose = createComposeRule()
    private fun text() = compose.onNodeWithTag("room-code-input").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    @Test fun deletionSelectionChineseCompositionAndPasteRemainEditableBeforeSubmit() {
        val hosted = ArrayList<String>(); val joined = ArrayList<String>()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            OnlineChessLobby(false, false, "", "", null, { hosted += it }, { joined += it }, {}, {}, game = "gomoku")
        } } }
        val field = compose.onNodeWithTag("room-code-input")
        field.performTextReplacement("aBcD")
        assertEquals("aBcD", text())
        field.performKeyInput { pressKey(Key.Backspace) }
        assertEquals("aBc", text())
        field.performTextInputSelection(TextRange(1, 3))
        field.performTextInput("中")
        assertEquals("a中", text())
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertTrue(hosted.isEmpty()) }
        compose.onNodeWithText("房间码用 4–12 位字母或数字").assertExists()
        field.performTextClearance()
        assertEquals("", text())
        field.performTextReplacement("  xyZ123  ")
        assertEquals("  xyZ123  ", text())
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertEquals(listOf("XYZ123"), hosted) }
        assertEquals("  xyZ123  ", text())
        compose.onNodeWithText("加入").performClick()
        field.performTextClearance()
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertTrue(joined.isEmpty()) }
        field.performTextReplacement("room5678")
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertEquals(listOf("ROOM5678"), joined) }
    }
}
