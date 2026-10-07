package com.jiligulu.app.ui.littleworld

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class SceneDialogLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var previousSound = true

    @Before fun quietFeedback() {
        previousSound = UiSound.enabled(RuntimeEnvironment.getApplication())
        UiSound.setEnabled(RuntimeEnvironment.getApplication(), false)
    }
    @After fun restoreFeedback() { UiSound.setEnabled(RuntimeEnvironment.getApplication(), previousSound) }

    @Test fun rematchTitleClearsTheFlowerAndActionsShareOneAlignedRow() {
        compose.setContent { MaterialTheme {
            SecretWoodDialog("再摆一盘？", {}, dismissLabel = "收桌", confirmLabel = "再来一局") {
                Text("棋友想再来一局。")
            }
        } }
        val paper = compose.onNodeWithTag("secret-wood-dialog").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("再摆一盘？").fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithTag("secret-wood-dismiss").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("secret-wood-confirm").fetchSemanticsNode().boundsInRoot
        assertEquals(paper.center.x, title.center.x, 1f)
        assertTrue("The title must sit below the corner flower", title.top - paper.top >= 40f)
        assertEquals(left.width, right.width, 1f)
        assertEquals(left.center.y, right.center.y, 1f)
        assertTrue(left.right < right.left)
        assertTrue(left.height >= 42f)
    }

    @Test fun undoChoicesDispatchExactlyOnceAndDoNotNeedAnExplanationRow() {
        var accepted = 0
        var declined = 0
        compose.setContent { MaterialTheme {
            SecretWoodDialog("棋友想悔棋", { declined++ }, dismissLabel = "继续这局", confirmLabel = "同意",
                onConfirm = { accepted++ }) {}
        } }
        compose.onNodeWithTag("secret-wood-confirm").performClick()
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(0, declined) }
        compose.onNodeWithTag("secret-wood-dismiss").performClick()
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(1, declined) }
    }

    @Test fun pendingRoomOperationDisablesBothFooterActions() {
        compose.setContent { MaterialTheme {
            SecretWoodDialog("正在收好棋桌", {}, busy = true, dismissLabel = "留下", confirmLabel = "收桌") {}
        } }
        compose.onNodeWithTag("secret-wood-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("secret-wood-dismiss").assertIsNotEnabled()
    }
}
