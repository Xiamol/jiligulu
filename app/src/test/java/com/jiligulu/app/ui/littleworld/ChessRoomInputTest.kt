package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class ChessRoomInputTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View

    private fun findEditor(view: View): RoomCodeEditText? {
        if (view is RoomCodeEditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun editor(): RoomCodeEditText = requireNotNull(findEditor(root.rootView))
    private fun connection(): InputConnection {
        lateinit var connection: InputConnection
        compose.runOnIdle {
            editor().requestFocus()
            connection = requireNotNull(editor().onCreateInputConnection(EditorInfo()))
        }
        return connection
    }
    private fun edit(operation: (InputConnection) -> Unit) {
        val connection = connection()
        compose.runOnIdle { operation(connection) }
        compose.waitForIdle()
    }
    private fun text(): String {
        var text = ""
        compose.runOnIdle { text = editor().text.toString() }
        return text
    }

    @Test fun realImeDeletionCompositionPasteAndSubmitAreSharedByCreationAndJoin() {
        val hosted = ArrayList<String>(); val joined = ArrayList<String>()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            root = LocalView.current
            OnlineChessLobby(false, false, "", "", null, { hosted += it }, { joined += it }, {}, {}, game = "gomoku")
        } } }
        edit { it.commitText("aBcD", 1) }
        assertEquals("aBcD", text())
        edit { it.deleteSurroundingText(1, 0) }
        assertEquals("aBc", text())
        edit { it.setSelection(1, 3); it.setComposingText("中", 1); it.finishComposingText() }
        assertEquals("a中", text())
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertTrue(hosted.isEmpty()) }
        compose.onNodeWithText("房间码用 4–12 位字母或数字").assertExists()
        edit { it.setSelection(0, 2); it.commitText("", 1) }
        assertEquals("", text())
        edit { it.commitText("  xyZ123  ", 1) }
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertEquals(listOf("XYZ123"), hosted) }
        assertEquals("  xyZ123  ", text())
        compose.onNodeWithText("加入").performClick()
        assertEquals("  xyZ123  ", text())
        edit { it.setSelection(0, 10); it.commitText("", 1) }
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertTrue(joined.isEmpty()) }
        edit { it.commitText("room5678", 1); it.deleteSurroundingText(4, 0); it.commitText("1234", 1) }
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertEquals(listOf("ROOM1234"), joined) }
    }

    @Test fun statusCodeBusyAndModeRedrawsCannotRefillOrResetTheLiveImeBuffer() {
        val serverCode = mutableStateOf("SERVER42")
        val status = mutableStateOf("等待")
        val busy = mutableStateOf(false)
        val hosted = ArrayList<String>(); val joined = ArrayList<String>()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            root = LocalView.current
            OnlineChessLobby(false, busy.value, serverCode.value, status.value, null,
                { hosted += it }, { joined += it }, {}, {}, game = "xiangqi")
        } } }
        edit { it.commitText("ABCDE9", 1); it.setSelection(2, 4) }
        val original = editor()
        compose.runOnIdle { serverCode.value = "OLDROOM7"; status.value = "新状态"; busy.value = true }
        compose.waitForIdle()
        assertSame(original, editor())
        assertEquals("ABCDE9", text())
        compose.runOnIdle { assertEquals(2, editor().selectionStart); assertEquals(4, editor().selectionEnd) }
        compose.runOnIdle { busy.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, editor().selectionStart); assertEquals(4, editor().selectionEnd) }
        edit { it.commitText("12", 1) }
        assertEquals("AB12E9", text())
        compose.onNodeWithText("加入").performClick()
        edit { input -> input.setSelection(6, 6); repeat(6) { input.deleteSurroundingTextInCodePoints(1, 0) } }
        assertEquals("", text())
        edit { it.commitText("abcd", 1) }
        compose.onNodeWithTag("room-submit").performClick()
        compose.runOnIdle { assertEquals(listOf("ABCD"), joined); assertTrue(hosted.isEmpty()) }
    }
}
