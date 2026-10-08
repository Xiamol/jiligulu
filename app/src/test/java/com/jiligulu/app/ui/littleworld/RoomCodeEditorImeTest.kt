package com.jiligulu.app.ui.littleworld

import android.app.Activity
import android.app.Application
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RoomCodeEditorImeTest {
    private fun editor(value: TextFieldValue = TextFieldValue(), test: (RoomCodeEditText, InputConnection) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = RoomCodeEditText(activity.get()).apply { restoreInitialValue(value) }
            activity.get().setContentView(view)
            view.requestFocus()
            test(view, requireNotNull(view.onCreateInputConnection(EditorInfo())))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun compositionAndSelectionSnapshotsTrackActualImeAndCanBeRestored() = editor { view, input ->
        var saved = TextFieldValue()
        view.onValueChange = { saved = it }
        input.commitText("ABCD", 1)
        input.setSelection(1, 3)
        input.setComposingText("xy", 1)
        assertEquals("AxyD", saved.text)
        assertEquals(TextRange(1, 3), saved.composition)
        assertEquals(TextRange(3), saved.selection)
        val restored = RoomCodeEditText(view.context).apply { restoreInitialValue(saved) }
        assertEquals("AxyD", restored.text.toString())
        assertEquals(3, restored.selectionStart)
        assertEquals(3, restored.selectionEnd)
        assertTrue(restored.text.getSpans(1, 3, Any::class.java).any {
            restored.text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0
        })
        input.finishComposingText()
        assertNull(saved.composition)
        input.deleteSurroundingText(2, 0)
        assertEquals("AD", saved.text)
        input.commitText("中", 1)
        assertEquals("A中D", saved.text)
    }

    @Test fun batchedImeDeleteAndReplacementPublishTheFinishedBufferWithoutFiltering() = editor { view, input ->
        val snapshots = ArrayList<TextFieldValue>()
        view.onValueChange = { snapshots += it }
        input.commitText("  abC123  ", 1)
        snapshots.clear()
        assertTrue(input.beginBatchEdit())
        input.setSelection(2, 5)
        input.commitText("xyz", 1)
        input.deleteSurroundingText(1, 0)
        input.endBatchEdit()
        assertEquals("  xy123  ", view.text.toString())
        assertEquals("  xy123  ", snapshots.last().text)
        assertEquals(TextRange(4), snapshots.last().selection)
        assertEquals("XY123", roomCodeSubmission(snapshots.last().text, joining = true).code)
        input.setSelection(0, view.text.length)
        input.commitText("", 1)
        assertEquals("", view.text.toString())
        assertNull(roomCodeSubmission(view.text.toString(), joining = true).code)
        assertEquals("", roomCodeSubmission(view.text.toString(), joining = false).code)
    }

    @Test fun temporarilyDisablingAndRefocusingPreservesTheUsersReplacementRange() = editor { view, input ->
        input.commitText("ABCDE9", 1)
        input.setSelection(2, 4)
        view.isEnabled = false
        view.clearFocus()
        view.isEnabled = true
        view.requestFocus()
        val resumed = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        assertEquals(2, view.selectionStart)
        assertEquals(4, view.selectionEnd)
        resumed.commitText("12", 1)
        assertEquals("AB12E9", view.text.toString())
        resumed.deleteSurroundingText(1, 0)
        assertEquals("AB1E9", view.text.toString())
    }

    @Test fun aNewUserTapAfterReenablingMayChooseANewCursorInsteadOfRestoringTheOldRange() = editor { view, input ->
        input.commitText("ABCDE9", 1)
        input.setSelection(2, 4)
        view.isEnabled = false
        view.clearFocus()
        view.isEnabled = true
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 48)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 24f, 0)
        val up = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 1f, 24f, 0)
        try { view.onTouchEvent(down); view.onTouchEvent(up) } finally { down.recycle(); up.recycle() }
        view.requestFocus()
        val resumed = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        assertEquals(0, view.selectionStart)
        assertEquals(0, view.selectionEnd)
        resumed.commitText("X", 1)
        assertEquals("XABCDE9", view.text.toString())
    }
}
