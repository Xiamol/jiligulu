package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class XiaoLiuRenStoreTest {
    @Test fun firstDailyHourAndChosenDateOrNumbersSurviveReopeningWithoutRerolling() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("liuren-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val today = LocalDate.of(2026, 10, 8)
        val initial = XiaoLiuRenInput(today, 8, 28, 5)
        var initialCalls = 0
        val store = XiaoLiuRenStore(preferences)
        assertEquals(initial, store.loadForDay(today) { initialCalls++; initial })
        assertEquals(initial, XiaoLiuRenStore(preferences).loadForDay(today) { error("same day must not use a later hour") })
        val selected = XiaoLiuRenInput(today.minusDays(1), 6, 5, 5, manualNumbers = true)
        store.save(today, selected)
        assertEquals(selected, XiaoLiuRenStore(preferences).loadForDay(today) { error("must restore the user's selection") })
        assertEquals(XiaoLiuRen.forInput(selected), XiaoLiuRen.forInput(store.loadForDay(today) { initial }))
        val next = initial.copy(date = today.plusDays(1), lunarDay = 29, shichen = 1)
        assertEquals(next, store.loadForDay(next.date) { initialCalls++; next })
        assertEquals(2, initialCalls)
        assertTrue(preferences.all.size <= 8)
    }
    @Test fun invalidSavedConditionsAreReplacedInsteadOfCrashingThePage() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("liuren-invalid-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val today = LocalDate.of(2026, 10, 8)
        preferences.edit().putString("anchor", today.toString()).putString("date", "not-a-date").putInt("month", 99).apply()
        val expected = XiaoLiuRenInput(today, 8, 28, 1)
        assertEquals(expected, XiaoLiuRenStore(preferences).loadForDay(today) { expected })
        assertEquals(expected, XiaoLiuRenStore(preferences).loadForDay(today) { error("repaired selection must persist") })
    }
    @Test fun savedQuestionCastAndDraftSurviveReopeningAndMidnightWithoutAnotherCalculation() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("liuren-session-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val cast = LiuRenCast("明天面试怎么准备？", LiuRenMode.NUMBERS,
            Instant.parse("2024-02-10T15:30:00Z").toEpochMilli(), "Asia/Shanghai", 1, 1, 1, digits = "012")
        val saved = LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast)
        XiaoLiuRenStore(preferences).saveSession(saved)
        assertEquals(saved, XiaoLiuRenStore(preferences).session())
        val draft = LiuRenSession("问".repeat(181), LiuRenMode.NUMBERS, "01", LiuRenStep.METHOD)
        XiaoLiuRenStore(preferences).saveSession(draft)
        assertEquals(draft, XiaoLiuRenStore(preferences).session()) // Validation must preserve unfinished input.
    }
    @Test fun malformedSnapshotCannotCrashTheRestoredPageAndAnalysisCacheIsBounded() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("liuren-cache-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        preferences.edit().putString("session_v2", """{"step":"RESULT","cast":null}""").apply()
        val store = XiaoLiuRenStore(preferences)
        assertEquals(LiuRenSession(), store.session())
        repeat(18) { store.saveAnalysis("key-$it", "解读-$it") }
        assertNull(store.analysis("key-0")); assertNull(store.analysis("key-1"))
        assertEquals("解读-17", XiaoLiuRenStore(preferences).analysis("key-17"))
        store.saveAnalysis("key-2", "更新后的解读")
        store.saveAnalysis("key-18", "新解读")
        assertEquals("更新后的解读", store.analysis("key-2")); assertNull(store.analysis("key-3"))
    }
    @Test fun horoscopeLegacyStampAndLiuRenStampAreIndependentAndPersistedUntilNextDay() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("liuren-stamp-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val today = LocalDate.of(2026, 10, 8)
        preferences.edit().putString("rewritten_day", today.toString()).apply()
        val usage = FortuneRewriteUsage(preferences)
        assertTrue(usage.used(FortuneRewriteKind.HOROSCOPE, today))
        assertFalse(usage.used(FortuneRewriteKind.LIU_REN, today))
        assertTrue(usage.mark(FortuneRewriteKind.LIU_REN, today))
        assertFalse(usage.mark(FortuneRewriteKind.LIU_REN, today))
        val reopened = FortuneRewriteUsage(preferences)
        assertTrue(reopened.used(FortuneRewriteKind.HOROSCOPE, today))
        assertTrue(reopened.used(FortuneRewriteKind.LIU_REN, today))
        assertFalse(reopened.used(FortuneRewriteKind.LIU_REN, today.plusDays(1)))
        assertTrue(reopened.mark(FortuneRewriteKind.HOROSCOPE, today.plusDays(1)))
        assertFalse(reopened.used(FortuneRewriteKind.LIU_REN, today.plusDays(1)))
    }
}
