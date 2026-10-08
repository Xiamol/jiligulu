package com.jiligulu.app.ui.main

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalTestApi::class)
class MainTabNavigationGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleTabsAndAccessibilityClicksUseLedgerStatisticsWorldOrder() {
        val selected = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState { MainPageCount }
                MainTabNavigation(pager, 0, selected::add, {}, {}, {})
            }
        }
        val ledger = compose.onNodeWithText("账本").getUnclippedBoundsInRoot()
        val statistics = compose.onNodeWithText("统计").getUnclippedBoundsInRoot()
        val world = compose.onNodeWithText("小窝").getUnclippedBoundsInRoot()
        assertTrue(ledger.left < statistics.left && statistics.left < world.left)
        compose.onNodeWithTag("main-tab-stats").performClick()
        compose.onNodeWithTag("main-tab-world").performClick()
        compose.onNodeWithTag("main-tab-home").performClick()
        compose.runOnIdle { assertEquals(listOf(1, 2, 0), selected) }
        assertEquals(MainDestination.STATISTICS, MainDestination.fromId("statistics"))
        assertEquals(MainDestination.WORLD, MainDestination.fromId("world"))
        assertEquals(MainDestination.LEDGER, MainDestination.fromId("unrecognized"))
    }

    @Test fun dragStartsOnFirstMoveAndReversesBeforeReleaseWithoutWaitingForLongPress() {
        val values = mutableListOf<Float>()
        val selections = mutableListOf<Int>()
        var releases = 0
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState { MainPageCount }
                Box(Modifier.fillMaxWidth()) {
                    MainTabNavigation(pager, 0, selections::add, {}, values::add, { releases++ })
                }
            }
        }
        val bar = compose.onNodeWithTag("main-tab-scrubber")
        bar.performTouchInput {
            down(Offset(width / 6f, height * .5f))
            moveTo(Offset(width * .7f, height * .5f), delayMillis = 16)
        }
        compose.runOnIdle {
            assertTrue("A MOVE before any long-press timeout must already drag", values.isNotEmpty())
            assertEquals(1.6f, values.last(), .01f)
            assertTrue(selections.isEmpty())
            assertEquals(0, releases)
        }
        bar.performTouchInput { moveTo(Offset(width * .3f, height * .5f), delayMillis = 16) }
        compose.runOnIdle { assertEquals(.4f, values.last(), .01f) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, releases); assertTrue(selections.isEmpty()) }
    }

    @Test fun stationaryTapSelectsExactlyOnceOnUpAndDoesNotJumpOnDown() {
        val values = mutableListOf<Float>()
        val selections = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState { MainPageCount }
                Box(Modifier.fillMaxWidth()) {
                    MainTabNavigation(pager, 0, selections::add, {}, values::add, {})
                }
            }
        }
        val bar = compose.onNodeWithTag("main-tab-scrubber")
        bar.performTouchInput { down(Offset(width * 5f / 6f, height * .5f)) }
        compose.runOnIdle { assertTrue(selections.isEmpty()); assertTrue(values.isEmpty()) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(2), selections); assertTrue(values.isEmpty()) }
        bar.performTouchInput { down(Offset(width * .5f, height * .5f)) }
        compose.runOnIdle { assertEquals(listOf(2), selections) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(2, 1), selections); assertTrue(values.isEmpty()) }
    }
}
