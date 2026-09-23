package com.jiligulu.app.ui.announcement

import org.junit.Assert.*
import org.junit.Test

class PromptPriorityTest {
    @Test fun updateGetsFirstTurnButDoesNotCoverAnOpenAnnouncement() {
        assertTrue(shouldShowUpdatePrompt(true, "new", null, false))
        assertFalse(shouldShowUpdatePrompt(true, "new", null, true))
        assertFalse(shouldShowUpdatePrompt(true, "new", "new", false))
        assertFalse(shouldShowUpdatePrompt(false, "new", null, false))
    }
}
