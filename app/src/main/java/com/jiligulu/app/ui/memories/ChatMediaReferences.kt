package com.jiligulu.app.ui.memories

import com.jiligulu.app.data.local.dao.ChatMediaReferenceRow
import com.jiligulu.app.ui.chat.DraftHistoryCodec

/** Metadata only; a malformed live payload stops deletion instead of guessing its references. */
internal fun chatMediaReferences(rows: List<ChatMediaReferenceRow>): List<String> = rows.flatMap { row ->
    when {
        row.status == "DELETED" || row.draftPayload.isBlank() -> emptyList()
        row.kind == "DRAFT" -> DraftHistoryCodec.decode(row.draftPayload).mapNotNull { it.photoUri }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}
