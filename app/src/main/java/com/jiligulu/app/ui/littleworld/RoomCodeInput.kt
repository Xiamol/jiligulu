package com.jiligulu.app.ui.littleworld

internal data class RoomCodeSubmission(val code: String? = null, val error: String? = null)

/** Editing is untouched; the existing protocol's canonical form is used only at submission. */
internal fun roomCodeSubmission(text: String, joining: Boolean): RoomCodeSubmission {
    if (text.isBlank()) return if (joining) RoomCodeSubmission(error = "先填棋友的房间码") else RoomCodeSubmission(code = "")
    return RoomRoundRules.code(text)?.let { RoomCodeSubmission(code = it) }
        ?: RoomCodeSubmission(error = "房间码用 4–12 位字母或数字")
}
