package com.jiligulu.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EdgeSpringMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lateScrollableDeltaAfterPointerUpCannotInterruptTheRebound() {
        lateinit var motion: EdgeSpringMotion
        lateinit var scope: CoroutineScope
        compose.setContent {
            motion = remember { EdgeSpringMotion(40f) }
            scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = motion.offset })
        }
        compose.runOnIdle {
            motion.beginTouch()
            motion.pull(100f)
            val held = motion.offset
            motion.release(scope) // Initial observes up first.
            motion.pull(30f)       // Main delivers its final scroll delta afterwards.
            assertTrue(motion.offset <= held)
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(0f, motion.offset, .01f) }
    }
}
