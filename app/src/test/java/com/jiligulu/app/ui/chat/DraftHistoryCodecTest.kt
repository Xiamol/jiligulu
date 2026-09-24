package com.jiligulu.app.ui.chat

import com.jiligulu.app.data.local.entity.BillType
import org.junit.Assert.*
import org.junit.Test

class DraftHistoryCodecTest {
    @Test fun editsAndTimeReviewStateSurviveRoundTrip() {
        val drafts = listOf(
            DraftUi(amountText = "9.5", type = BillType.EXPENSE, detail = "午饭", timestamp = 1790000000000L,
                note = "补记", checked = true, timeHint = "手动调整"),
            DraftUi(amountText = "1200", type = BillType.INCOME, detail = "报销", checked = false, timeNeedsReview = true)
        )
        assertEquals(drafts, DraftHistoryCodec.decode(DraftHistoryCodec.encode(drafts)))
    }

    @Test fun payloadFromEarlierVersionGetsNewFieldDefaults() {
        val drafts = DraftHistoryCodec.decode("""{"drafts":[{"amountText":"9","type":"EXPENSE","detail":"午饭"}]}""")
        assertNull(drafts.single().timestamp)
        assertFalse(drafts.single().timeNeedsReview)
        assertTrue(drafts.single().isValid)
    }

    @Test fun fieldsFromFutureVersionDoNotDestroyHistory() {
        val drafts = DraftHistoryCodec.decode("""{"version":1,"futureFlag":true,"drafts":[{"amountText":"9","futureField":"ok"}]}""")
        assertEquals("9", drafts.single().amountText)
    }

    @Test fun unresolvedTimeOrInvalidAmountCannotBeConfirmed() {
        assertFalse(DraftUi(amountText = "0").isValid)
        assertFalse(DraftUi(amountText = "9", timeNeedsReview = true).isValid)
        assertTrue(DraftUi(amountText = "9", timestamp = null).isValid)
    }
    @Test fun visibleSuggestedTimeCanBeAcceptedWithoutOpeningPicker() {
        val draft = DraftUi(amountText = "9", timestamp = 1790000000000L, timeNeedsReview = true)
        assertTrue(draft.isValid)
        assertFalse(draft.requiresTimeInput)
        assertTrue(DraftHistoryCodec.decode(DraftHistoryCodec.encode(listOf(draft))).single().isValid)
        assertFalse(draft.copy(timestamp = null).isValid)
        assertFalse(draft.copy(amountText = "0").isValid)
    }
}
