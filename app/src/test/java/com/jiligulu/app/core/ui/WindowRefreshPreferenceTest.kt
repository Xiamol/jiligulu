package com.jiligulu.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowRefreshPreferenceTest {
    private val current = DisplayRateMode(1080, 2400, 60f)
    private val supported = listOf(current, current.copy(refreshRate = 90f), current.copy(refreshRate = 120f))
    private fun choose(api: Int = 34, saver: Boolean = false, modeId: Int = 0, rate: Float = 0f,
        active: DisplayRateMode = current, modes: List<DisplayRateMode> = supported) =
        selectSmoothRefreshRate(api, saver, modeId, rate, active, modes)

    @Test fun onlyRequestsActualHigherRateAtCurrentResolution() {
        assertEquals(120f, choose()!!, .001f)
        assertEquals(90f, choose(modes = supported.take(2))!!, .001f)
        assertNull(choose(modes = listOf(current, DisplayRateMode(1440, 3200, 144f))))
        assertNull(choose(active = current.copy(refreshRate = 120f)))
    }

    @Test fun retainsSystemPolicyBeforeAndroid14AndInBatterySaver() {
        assertNull(choose(api = 33))
        assertNull(choose(saver = true))
        assertNull(choose(modes = listOf(current)))
    }

    @Test fun neverOverridesAnotherPreference() {
        assertNull(choose(modeId = 1))
        assertNull(choose(rate = 90f))
    }

    @Test fun ignoresInvalidRatesAndTinyRefreshRoundingDifferences() {
        assertNull(choose(modes = listOf(current.copy(refreshRate = Float.NaN), current.copy(refreshRate = Float.POSITIVE_INFINITY))))
        assertNull(choose(active = current.copy(refreshRate = 59.94f), modes = listOf(current)))
    }
}
