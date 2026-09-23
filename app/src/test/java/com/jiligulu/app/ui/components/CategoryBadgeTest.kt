package com.jiligulu.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryBadgeTest {
    @Test fun `use real emoji including combined sequences`() {
        listOf("🍚", "❤️", "⭐", "👩‍💻", "🇨🇳", "1️⃣").forEach { assertEquals(it, categoryBadgeGlyph("分类", it)) }
    }

    @Test fun `missing invalid and legacy placeholders fall back to first category character`() {
        listOf("", " ", "🫧", "🫧️", "restaurant", "<svg/>", "🍚吃饭").forEach {
            assertEquals("吃", categoryBadgeGlyph("吃饭", it))
        }
        assertEquals("吃", categoryBadgeGlyph("eating", ""))
        assertEquals("未", categoryBadgeGlyph("", ""))
        assertEquals("𠮷", categoryBadgeGlyph("𠮷祥", ""))
    }
}
