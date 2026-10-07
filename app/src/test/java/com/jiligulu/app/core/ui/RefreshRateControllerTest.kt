package com.jiligulu.app.core.ui

import com.jiligulu.app.data.prefs.AppRefreshRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateControllerTest {
    private val current = DisplayRateMode(1080, 2400, 120f, 3)
    private val sixty = current.copy(refreshRate = 60f, id = 1)
    private val ninety = current.copy(refreshRate = 90f, id = 2)
    private val supported = listOf(sixty, ninety, current)
    private fun choose(rate: AppRefreshRate, saver: Boolean = false,
        active: DisplayRateMode = current, modes: List<DisplayRateMode> = supported) =
        selectAppRefreshMode(rate, saver, active, modes)

    @Test fun systemDefaultDoesNotForceHighRefresh() {
        assertEquals(AppRefreshRate.SYSTEM, AppRefreshRate.fromHertz(null))
        assertEquals(AppRefreshRate.SYSTEM, AppRefreshRate.fromHertz(144))
        assertNull(choose(AppRefreshRate.SYSTEM))
        assertNull(choose(AppRefreshRate.SYSTEM, saver = true))
    }

    @Test fun capsAtRequestedSupportedRateAndKeepsResolution() {
        assertEquals(sixty, choose(AppRefreshRate.HZ_60))
        assertEquals(ninety, choose(AppRefreshRate.HZ_90))
        assertEquals(current, choose(AppRefreshRate.HZ_120))
        assertEquals(sixty, choose(AppRefreshRate.HZ_90, modes = listOf(sixty, current)))
        assertEquals(sixty, choose(AppRefreshRate.HZ_120, modes = listOf(sixty,
            DisplayRateMode(1440, 3200, 120f, 7))))
        assertNull(choose(AppRefreshRate.HZ_60, modes = listOf(current)))
    }

    @Test fun respectsBatterySaverWithoutOverwritingSavedChoice() {
        assertEquals(sixty, choose(AppRefreshRate.HZ_120, saver = true))
        assertEquals(sixty, choose(AppRefreshRate.HZ_90, saver = true))
        assertEquals(current, choose(AppRefreshRate.HZ_120))
    }

    @Test fun invalidDisplaysAndRatesNeverCauseAModeChange() {
        assertNull(choose(AppRefreshRate.HZ_120, active = current.copy(width = 0)))
        assertNull(choose(AppRefreshRate.HZ_120, modes = listOf(
            current.copy(refreshRate = Float.NaN), current.copy(refreshRate = Float.POSITIVE_INFINITY),
            current.copy(refreshRate = 0f))))
        assertEquals(sixty.copy(refreshRate = 59.94f), choose(AppRefreshRate.HZ_60,
            modes = listOf(sixty.copy(refreshRate = 59.94f), current)))
    }

    @Test fun whenPhysicalResolutionChangesOnlyMatchingModesAreChosen() {
        val landscape = current.copy(width = 2400, height = 1080, refreshRate = 60f, id = 8)
        assertEquals(landscape, choose(AppRefreshRate.HZ_120, active = landscape,
            modes = supported + landscape))
    }
}
