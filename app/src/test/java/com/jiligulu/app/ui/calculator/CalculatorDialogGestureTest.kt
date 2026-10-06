package com.jiligulu.app.ui.calculator

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.runtime.snapshots.Snapshot
import com.jiligulu.app.core.audio.UiSound
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class CalculatorDialogGestureTest {
    @get:Rule val compose = createComposeRule()
    private var soundWasEnabled = true

    @Before fun quietFeedback() {
        soundWasEnabled = UiSound.enabled(RuntimeEnvironment.getApplication())
        UiSound.setEnabled(RuntimeEnvironment.getApplication(), false)
    }
    @After fun restoreFeedback() { UiSound.setEnabled(RuntimeEnvironment.getApplication(), soundWasEnabled) }

    private fun open(initial: String = "") {
        compose.setContent { MaterialTheme { CalculatorDialog(initial, {}, {}) } }
        compose.mainClock.autoAdvance = false
    }
    private fun expression() = compose.onNodeWithTag("calculator-expression").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text
    private fun elapse(millis: Long) {
        val androidTarget = SystemClock.uptimeMillis() + millis
        compose.mainClock.advanceTimeBy(millis)
        // Some harnesses advance Android time together with Compose; never advance it twice.
        val remaining = (androidTarget - SystemClock.uptimeMillis()).coerceAtLeast(0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(remaining))
        Snapshot.sendApplyNotifications()
    }
    // A Dialog has its own Android window/recomposer. Pump both clocks and snapshot notifications.
    private fun frame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        elapse(32)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun hold(key: SemanticsNodeInteraction, millis: Long) {
        key.performTouchInput { down(center); advanceEventTime(millis) }
        elapse(millis)
    }

    @Test fun keyboardEditingIsExplicitAndReturningAlwaysRestoresTheKeypad() {
        open()
        compose.onNodeWithTag("calculator-key-7").assertExists()
        compose.onNodeWithTag("calculator-keyboard-switch").performClick(); frame()
        compose.onNodeWithTag("calculator-key-7").assertDoesNotExist()
        compose.onNodeWithTag("calculator-expression").performTextInput("9"); frame()
        assertEquals("9", expression())
        compose.onNodeWithTag("calculator-keyboard-switch").performClick(); frame()
        compose.onNodeWithTag("calculator-key-7").assertExists().performClick(); frame()
        assertEquals("97", expression())
    }

    @Test fun touchingTheReadOnlyExpressionIsTheOtherExplicitKeyboardEntry() {
        open("12")
        compose.onNodeWithTag("calculator-key-7").assertExists()
        compose.onNodeWithTag("calculator-edit-expression").performClick(); frame()
        compose.onNodeWithTag("calculator-key-7").assertDoesNotExist()
        compose.onNodeWithTag("calculator-keyboard-switch").performClick(); frame()
        compose.onNodeWithTag("calculator-key-7").assertExists()
        assertEquals("12", expression())
    }

    @Test fun releaseAfter650msDoesNotDeleteOrClearTheNextDigit() {
        open("12345678901234567890")
        val delete = compose.onNodeWithTag("calculator-key-⌫")
        hold(delete, 650)
        delete.performTouchInput { up() }; frame()
        assertEquals("", expression())
        compose.onNodeWithTag("calculator-key-7").performClick(); frame()
        elapse(1000); frame()
        assertEquals("7", expression())
    }

    @Test fun canceledDeletionCannotKeepRepeatingAfterThePointerLeaves() {
        open("12345678901234567890")
        val delete = compose.onNodeWithTag("calculator-key-⌫")
        hold(delete, 350)
        delete.performTouchInput { cancel() }; frame()
        val afterCancel = expression()
        assertTrue(afterCancel.isNotEmpty() && afterCancel.length < 20)
        elapse(1000); frame()
        assertEquals(afterCancel, expression())
    }

    @Test fun firstShortDeleteAfterAHeldClearDeletesExactlyOneDigit() {
        open("12345678901234567890")
        val delete = compose.onNodeWithTag("calculator-key-⌫")
        hold(delete, 650)
        delete.performTouchInput { up() }; frame()
        for (digit in listOf("1", "2", "3")) { compose.onNodeWithTag("calculator-key-$digit").performClick(); frame() }
        delete.performTouchInput { down(center); advanceEventTime(20); up() }; frame()
        assertEquals("12", expression())
    }

    @Test fun holdingANumberStillCommitsOneInputOnRelease() {
        open()
        val five = compose.onNodeWithTag("calculator-key-5")
        hold(five, 650)
        five.performTouchInput { up() }; frame()
        assertEquals("5", expression())
    }

    @Test fun distinctKeysEightMillisecondsApartAreBothAccepted() {
        open()
        val keypad = compose.onNodeWithTag("calculator-keypad")
        val origin = keypad.fetchSemanticsNode().boundsInRoot.topLeft
        val seven = compose.onNodeWithTag("calculator-key-7").fetchSemanticsNode().boundsInRoot.center - origin
        val eight = compose.onNodeWithTag("calculator-key-8").fetchSemanticsNode().boundsInRoot.center - origin
        keypad.performTouchInput { down(seven); up(); advanceEventTime(8); down(eight); up() }; frame()
        assertEquals("78", expression())
    }

    @Test fun equalsUsesTheDigitAcceptedBeforeAnotherDisplayFrame() {
        open("12+")
        val keypad = compose.onNodeWithTag("calculator-keypad")
        val origin = keypad.fetchSemanticsNode().boundsInRoot.topLeft
        val three = compose.onNodeWithTag("calculator-key-3").fetchSemanticsNode().boundsInRoot.center - origin
        val equals = compose.onNodeWithTag("calculator-key-=").fetchSemanticsNode().boundsInRoot.center - origin
        keypad.performTouchInput { down(three); up(); advanceEventTime(8); down(equals); up() }; frame()
        assertEquals("15", expression())
    }
}
