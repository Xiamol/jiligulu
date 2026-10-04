package com.jiligulu.app.ui.main

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class MainTabNavigationGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragStartsOnFirstMoveAndReversesBeforeReleaseWithoutWaitingForLongPress() {
        val values = mutableListOf<Float>()
        val selections = mutableListOf<Int>()
        var releases = 0
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState { 2 }
                Box(Modifier.fillMaxWidth()) {
                    MainTabNavigation(pager, 0, selections::add, {}, values::add, { releases++ })
                }
            }
        }
        val bar = compose.onNodeWithTag("main-tab-scrubber")
        bar.performTouchInput {
            down(Offset(width * .25f, height * .5f))
            moveTo(Offset(width * .65f, height * .5f), delayMillis = 16)
        }
        compose.runOnIdle {
            assertTrue("A MOVE before any long-press timeout must already drag", values.isNotEmpty())
            assertEquals(.8f, values.last(), .01f)
            assertTrue(selections.isEmpty())
            assertEquals(0, releases)
        }
        bar.performTouchInput { moveTo(Offset(width * .35f, height * .5f), delayMillis = 16) }
        compose.runOnIdle { assertEquals(.2f, values.last(), .01f) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, releases); assertTrue(selections.isEmpty()) }
    }

    @Test fun stationaryTapSelectsExactlyOnceOnUpAndDoesNotJumpOnDown() {
        val values = mutableListOf<Float>()
        val selections = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState { 2 }
                Box(Modifier.fillMaxWidth()) {
                    MainTabNavigation(pager, 0, selections::add, {}, values::add, {})
                }
            }
        }
        val bar = compose.onNodeWithTag("main-tab-scrubber")
        bar.performTouchInput { down(Offset(width * .75f, height * .5f)) }
        compose.runOnIdle { assertTrue(selections.isEmpty()); assertTrue(values.isEmpty()) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(1), selections); assertTrue(values.isEmpty()) }
    }
}
