package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The native editor owns its live IME buffer. The Compose value is a saved snapshot, never a
 * per-recomposition replacement of Editable: doing that would discard selection/composition and
 * can undo an IME deletion. Canonical room-code validation belongs only to submission.
 */
@Composable
internal fun RoomCodeEditor(initialValue: TextFieldValue, enabled: Boolean, hint: String,
    onValueChange: (TextFieldValue) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val textSizePx = with(density) { MaterialTheme.typography.titleMedium.fontSize.toPx() }
    val hintSizePx = with(density) { MaterialTheme.typography.bodySmall.fontSize.toPx() }
    val letterSpacingPx = with(density) { 2.sp.toPx() }
    AndroidView(modifier = modifier, factory = { context ->
        RoomCodeEditText(context).apply { restoreInitialValue(initialValue) }
    }, update = { editor ->
        editor.onValueChange = onValueChange
        editor.onSubmit = onSubmit
        editor.isEnabled = enabled
        editor.setTextColor(ChessLobbyColors.ink.toArgb())
        editor.setHintTextColor(ChessLobbyColors.muted.toArgb())
        editor.highlightColor = ChessLobbyColors.accent.copy(alpha = .22f).toArgb()
        editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        editor.letterSpacing = letterSpacingPx / textSizePx
        if (editor.hint?.toString() != hint) editor.hint = SpannableString(hint).apply {
            setSpan(RelativeSizeSpan(hintSizePx / textSizePx), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // In particular, do not call setText/setSelection here when lobby status or tabs redraw.
    })
}

internal class RoomCodeEditText(context: Context) : EditText(context) {
    var onValueChange: ((TextFieldValue) -> Unit)? = null
    var onSubmit: (() -> Unit)? = null
    private var ready = false
    private var imeBatchDepth = 0
    private var lastPublished: TextFieldValue? = null

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        contentDescription = "房间码"
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { publishValue() }
        })
        setOnEditorActionListener { _, action, event ->
            val submit = action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submit && isEnabled) onSubmit?.invoke()
            submit
        }
    }

    /** Applied only when this editor is created/restored, never on a lobby recomposition. */
    fun restoreInitialValue(value: TextFieldValue) {
        ready = false
        setText(value.text)
        val length = text.length
        setSelection(value.selection.start.coerceIn(0, length), value.selection.end.coerceIn(0, length))
        value.composition?.let { range ->
            val start = range.min.coerceIn(0, length)
            val end = range.max.coerceIn(start, length)
            if (end > start) super.onCreateInputConnection(EditorInfo())?.setComposingRegion(start, end)
        }
        lastPublished = snapshotValue()
        ready = true
    }

    private fun snapshotValue(): TextFieldValue {
        val editable = text
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        val composition = if (start >= 0 && end >= start) TextRange(start, end) else null
        return TextFieldValue(editable.toString(), TextRange(selectionStart.coerceIn(0, editable.length),
            selectionEnd.coerceIn(0, editable.length)), composition)
    }

    private fun publishValue() {
        if (!ready || imeBatchDepth > 0) return
        val value = snapshotValue()
        if (lastPublished != value) {
            lastPublished = value
            onValueChange?.invoke(value)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        publishValue()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val delegate = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(delegate, false) {
            override fun beginBatchEdit(): Boolean = super.beginBatchEdit().also { if (it) imeBatchDepth++ }
            override fun endBatchEdit(): Boolean = super.endBatchEdit().also {
                if (imeBatchDepth > 0) imeBatchDepth--
                publishValue()
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { publishValue() }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.setComposingText(text, newCursorPosition).also { publishValue() }
            override fun setComposingRegion(start: Int, end: Int): Boolean =
                super.setComposingRegion(start, end).also { publishValue() }
            override fun finishComposingText(): Boolean = super.finishComposingText().also { publishValue() }
            override fun setSelection(start: Int, end: Int): Boolean =
                super.setSelection(start, end).also { publishValue() }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                super.deleteSurroundingText(beforeLength, afterLength).also { publishValue() }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { publishValue() }
            override fun closeConnection() {
                super.closeConnection()
                imeBatchDepth = 0
                publishValue()
            }
        }
    }
}
