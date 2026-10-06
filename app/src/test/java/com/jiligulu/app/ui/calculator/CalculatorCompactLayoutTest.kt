package com.jiligulu.app.ui.calculator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w610dp-h400dp-land-mdpi")
class CalculatorCompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyKeyAndTheUseActionRemainVisibleInAShortLandscapeWindow() {
        compose.setContent { MaterialTheme { CalculatorDialog("12+3", {}, {}) } }
        val panel = compose.onNodeWithTag("calculator-panel").fetchSemanticsNode().boundsInRoot
        for (key in listOf("C", "(", ")", "⌫", "7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "0", ".", "=", "+")) {
            val bounds = compose.onNodeWithTag("calculator-key-$key").fetchSemanticsNode().boundsInRoot
            assertTrue("$key must have a readable height, not a collapsed strip", bounds.height >= 20f)
            assertTrue("$key must stay inside the dialog", bounds.top >= panel.top && bounds.bottom <= panel.bottom + 1f)
        }
        val use = compose.onNodeWithTag("calculator-use").fetchSemanticsNode().boundsInRoot
        assertTrue(use.height >= 30f && use.bottom <= panel.bottom + 1f)
    }
}
