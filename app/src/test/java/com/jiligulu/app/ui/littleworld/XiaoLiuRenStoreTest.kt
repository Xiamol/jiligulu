package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import java.time.LocalDate
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
}
