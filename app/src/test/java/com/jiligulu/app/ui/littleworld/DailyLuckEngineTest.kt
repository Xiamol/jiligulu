package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyLuckEngineTest {
    @Test fun sameCalendarDayAndSignAreStableAcrossRepeatedVisits() {
        val date = LocalDate.of(2026, 10, 7)
        assertEquals(DailyLuckEngine.forDate(date, "天秤", false), DailyLuckEngine.forDate(date, "天秤", false))
        assertEquals(DailyLuckEngine.forDate(date, "未知", false), DailyLuckEngine.forDate(date, "随缘星座", false))
    }
    @Test fun rewritingRaisesThePlayfulScoresWithoutChangingTheDailyAdvice() {
        val date = LocalDate.of(2026, 10, 7)
        val before = DailyLuckEngine.forDate(date, "双鱼", false)
        val after = DailyLuckEngine.forDate(date, "双鱼", true)
        assertEquals(5, after.mood)
        assertTrue(after.inspiration >= before.inspiration)
        assertEquals(before.goodFor, after.goodFor)
        assertEquals(before.letGo, after.letGo)
    }
    @Test fun everyWheelSegmentHasVarietyAndKnownNavigationTargets() {
        assertEquals(6, FortuneWheelTasks.groups.size)
        assertTrue(FortuneWheelTasks.groups.flatten().size >= 20)
        assertTrue(FortuneWheelTasks.groups.flatten().all { it.destination in listOf("", "future", "memories", "paper") })
    }
}
