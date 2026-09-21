package com.jiligulu.app.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonaEngineTest {
    private fun engine(vararg entries: QuipEntry) = PersonaEngine(QuipLibrary(entries.toList()))

    @Test
    fun `water reminder is not swallowed by a recent greeting`() {
        val engine = engine(
            QuipEntry(PersonaEngine.TYPE_GREET, "早上好"),
            QuipEntry(PersonaEngine.TYPE_WATER, "喝口水"),
        )

        assertEquals("早上好", engine.nextGreeting(0L, ""))
        assertEquals("喝口水", engine.nextWaterQuip(1L, ""))
    }

    @Test
    fun `a reminder arriving just before a tick keeps its full display interval`() {
        val engine = engine(
            QuipEntry(PersonaEngine.TYPE_WEIRD, "聊两句"),
            QuipEntry(PersonaEngine.TYPE_WATER, "喝口水"),
        )
        engine.markCompanionShown(0L)
        assertEquals("聊两句", engine.nextIdleQuip(10_000L, ""))
        engine.markCompanionShown(10_000L)

        assertEquals("喝口水", engine.nextWaterQuip(19_900L, ""))
        engine.markCompanionShown(19_900L)

        assertNull(engine.nextIdleQuip(20_000L, "", "喝口水"))
        assertNull(engine.nextIdleQuip(29_899L, "", "喝口水"))
        assertEquals("聊两句", engine.nextIdleQuip(29_900L, "", "喝口水"))
    }

    @Test
    fun `returning to a visible host starts a fresh interval`() {
        val engine = engine(QuipEntry(PersonaEngine.TYPE_POEM, "新一句"))
        engine.markCompanionShown(0L)
        engine.markCompanionShown(100_000L)

        assertNull(engine.nextIdleQuip(109_999L, ""))
        assertEquals("新一句", engine.nextIdleQuip(110_000L, ""))
    }

    @Test
    fun `a greeting just after ticker start only adds its remaining milliseconds`() {
        val engine = engine(QuipEntry(PersonaEngine.TYPE_WEIRD, "下一句"))
        engine.markCompanionShown(0L)
        assertEquals(10_000L, engine.millisUntilIdleRefresh(0L))

        engine.markCompanionShown(3L)
        assertEquals(3L, engine.millisUntilIdleRefresh(10_000L))
        assertNull(engine.nextIdleQuip(10_000L, ""))
        assertEquals(1L, engine.millisUntilIdleRefresh(10_002L))
        assertEquals(0L, engine.millisUntilIdleRefresh(10_003L))
        assertEquals("下一句", engine.nextIdleQuip(10_003L, ""))
    }

    @Test
    fun `a click just before a pending refresh extends the deadline from the click`() {
        val engine = engine(QuipEntry(PersonaEngine.TYPE_WEIRD, "下一句"))
        engine.markCompanionShown(0L)
        engine.markCompanionShown(9_997L)

        assertEquals(9_997L, engine.millisUntilIdleRefresh(10_000L))
        assertEquals(1L, engine.millisUntilIdleRefresh(19_996L))
        assertEquals(0L, engine.millisUntilIdleRefresh(19_997L))
        assertEquals("下一句", engine.nextIdleQuip(19_997L, ""))
        assertEquals(10_000L, engine.millisUntilIdleRefresh(19_997L))
    }

    @Test
    fun `elapsed deadlines never return a negative delay`() {
        val engine = engine(QuipEntry(PersonaEngine.TYPE_WEIRD, "下一句"))
        assertEquals(0L, engine.millisUntilIdleRefresh(0L))
        engine.markCompanionShown(100L)

        assertEquals(0L, engine.millisUntilIdleRefresh(100_000L))
    }

    @Test
    fun `manual clicks can change lines immediately during the automatic hold`() {
        val engine = engine(
            QuipEntry(PersonaEngine.TYPE_POEM, "甲"),
            QuipEntry(PersonaEngine.TYPE_WEIRD, "乙"),
        )
        engine.markCompanionShown(0L)
        assertNull(engine.nextIdleQuip(1L, "", "甲"))

        var previous = "甲"
        repeat(20) {
            val next = engine.idleQuipNow("", previous)
            assertNotNull(next)
            assertNotEquals(previous, next)
            previous = next!!
        }
    }

    @Test
    fun `different templates rendering the same text are all excluded`() {
        val engine = engine(
            QuipEntry(PersonaEngine.TYPE_POEM, "你好{n}"),
            QuipEntry(PersonaEngine.TYPE_WEIRD, "你好，朋友"),
            QuipEntry(PersonaEngine.TYPE_POEM, "换个话题"),
        )

        repeat(20) {
            assertEquals("换个话题", engine.idleQuipNow("朋友", "你好，朋友"))
        }
    }

    @Test
    fun `automatic refresh also excludes the current rendered line`() {
        val engine = engine(
            QuipEntry(PersonaEngine.TYPE_POEM, "你好{n}"),
            QuipEntry(PersonaEngine.TYPE_WEIRD, "新话题"),
        )
        engine.markCompanionShown(0L)

        assertEquals("新话题", engine.nextIdleQuip(10_000L, "朋友", "你好，朋友"))
    }

    @Test
    fun `a single available line still provides feedback and empty libraries return null`() {
        val single = engine(QuipEntry(PersonaEngine.TYPE_WEIRD, "只有一句"))

        assertEquals("只有一句", single.idleQuipNow("", "只有一句"))
        assertNull(engine().idleQuipNow("", "上一句"))
    }

    @Test
    fun `save throttling accepts the first event even at clock zero`() {
        val engine = engine(QuipEntry(PersonaEngine.TYPE_SAVE, "记好了"))

        assertEquals("记好了", engine.nextSaveQuip(0L, ""))
        assertNull(engine.nextSaveQuip(4_999L, ""))
        assertEquals("记好了", engine.nextSaveQuip(5_000L, ""))
    }
}
