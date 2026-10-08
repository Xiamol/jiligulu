package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class RoomCodeInputTest {
    @Test fun emptyHostMeansGenerateButEmptyJoinNeedsAnInput() {
        assertEquals("", roomCodeSubmission("", joining = false).code)
        assertEquals("", roomCodeSubmission("   ", joining = false).code)
        assertNull(roomCodeSubmission("", joining = true).code)
        assertNotNull(roomCodeSubmission("", joining = true).error)
    }
    @Test fun normalizationOccursOnlyWhenSubmittingAndKeepsTheExistingWireRules() {
        val raw = "  aBc123  "
        assertEquals("ABC123", roomCodeSubmission(raw, joining = true).code)
        assertEquals(RoomRoundRules.code(raw), roomCodeSubmission(raw, joining = false).code)
        assertEquals("  aBc123  ", raw)
        for (text in listOf("abc", "a".repeat(13), "abc中123", "ab cd", "ab-cd", "房间码")) {
            assertNull(text, roomCodeSubmission(text, joining = true).code)
            assertNotNull(roomCodeSubmission(text, joining = false).error)
        }
    }
}
