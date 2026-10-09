package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.theme.GuluTypography
import java.time.Instant
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
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class FortuneWheelGameLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var previousSound = true
    private val fixedRegions = listOf("fortune-tabs", "fortune-body", "fortune-footer")

    @Before fun clearSavedPagesAndQuietFeedback() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("gulu_daily_luck", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("gulu_xiao_liuren", Context.MODE_PRIVATE).edit().clear().commit()
        previousSound = UiSound.enabled(context)
        UiSound.setEnabled(context, false)
    }

    @After fun restoreFeedback() {
        UiSound.setEnabled(RuntimeEnvironment.getApplication(), previousSound)
    }

    @Test fun tabsBodyAndFooterKeepTheirBoundsAcrossPagesAndExpandedReadingScroll() {
        // Reopening a saved local result and expanding its explanation never requests AI.
        val cast = LiuRenCast("这周面试，我该怎样准备？", LiuRenMode.NUMBERS,
            Instant.parse("2026-10-09T06:00:00Z").toEpochMilli(), "Asia/Shanghai", 8, 29, 8, digits = "840")
        XiaoLiuRenStore(RuntimeEnvironment.getApplication().getSharedPreferences("gulu_xiao_liuren", Context.MODE_PRIVATE))
            .saveSession(LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast))
        showPage(360.dp, 620.dp)

        val initial = regionBounds()
        assertEquals(48f, (initial.getValue("fortune-tabs").bottom - initial.getValue("fortune-tabs").top).value, .5f)
        assertEquals(88f, (initial.getValue("fortune-footer").bottom - initial.getValue("fortune-footer").top).value, .5f)
        assertEquals(initial.getValue("fortune-body").bottom.value,
            initial.getValue("fortune-footer").top.value, .5f)
        for (page in listOf("luck", "liuren", "wheel", "liuren")) {
            compose.onNodeWithTag("fortune-tab-$page").performClick()
            assertRegionBounds(initial)
        }

        compose.onNodeWithTag("liuren-details-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertRegionBounds(initial)
        val scrollMatcher = SemanticsMatcher("the fortune body's vertical scroll owner") { node ->
            node.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                node.config.contains(SemanticsActions.ScrollBy)
        } and hasAnyAncestor(hasTestTag("fortune-body"))
        assertEquals("An expanded reading must use a single vertical scroll owner", 1,
            compose.onAllNodes(scrollMatcher, useUnmergedTree = true).fetchSemanticsNodes().size)
        val scroll = compose.onNode(scrollMatcher, useUnmergedTree = true)
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The expanded explanation must exceed the body viewport",
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 320f) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("The reading must actually scroll",
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > before)
        assertRegionBounds(initial)
        assertInside("liuren-rewrite", initial.getValue("fortune-footer"))
    }

    @Test fun shortNarrowViewportWithLargerTextKeepsTabsAndActionsInsideTheirRails() {
        showPage(320.dp, 420.dp, fontScale = 1.3f)
        val initial = regionBounds()
        val frame = bounds("fortune-test-frame")
        assertEquals(320f, (frame.right - frame.left).value, .5f)
        assertEquals(420f, (frame.bottom - frame.top).value, .5f)
        fixedRegions.forEach { assertInside(it, frame) }
        for (page in listOf("wheel", "luck", "liuren")) {
            assertInside("fortune-tab-$page", initial.getValue("fortune-tabs"))
        }
        assertInside("secret-prize-wheel", initial.getValue("fortune-body"))

        compose.onNodeWithTag("fortune-tab-luck").performClick()
        assertRegionBounds(initial)
        assertInside("luck-rewrite", initial.getValue("fortune-footer"))
        compose.onNodeWithTag("fortune-tab-liuren").performClick()
        assertRegionBounds(initial)
        assertInside("liuren-next", initial.getValue("fortune-footer"))

        // Moving from a long draft to method choice only changes local session state.
        compose.onNodeWithTag("liuren-question").performTextReplacement("面试准备的内容我想逐项整理，看看怎样安排更合适。".repeat(5))
        compose.onNodeWithTag("liuren-next").performClick()
        assertRegionBounds(initial)
        assertInside("liuren-back", initial.getValue("fortune-footer"))
        assertInside("liuren-cast", initial.getValue("fortune-footer"))
    }

    private fun showPage(width: Dp, height: Dp, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme(typography = GuluTypography) {
                    Box(Modifier.size(width, height).testTag("fortune-test-frame")) {
                        Column(Modifier.fillMaxSize()) {
                            FortuneWheelGame(300.dp, foreground = false, {}, {}, {})
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun regionBounds() = fixedRegions.associateWith(::bounds)

    private fun assertRegionBounds(expected: Map<String, DpRect>) {
        fixedRegions.forEach { tag ->
            val before = expected.getValue(tag)
            val after = bounds(tag)
            assertEquals("$tag left must stay fixed", before.left.value, after.left.value, .5f)
            assertEquals("$tag top must stay fixed", before.top.value, after.top.value, .5f)
            assertEquals("$tag right must stay fixed", before.right.value, after.right.value, .5f)
            assertEquals("$tag bottom must stay fixed", before.bottom.value, after.bottom.value, .5f)
        }
    }

    private fun assertInside(tag: String, container: DpRect) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val region = bounds(tag)
        assertTrue("$tag must have measurable width and height", region.right > region.left && region.bottom > region.top)
        assertTrue("$tag must stay horizontally inside its container",
            region.left.value >= container.left.value - .5f && region.right.value <= container.right.value + .5f)
        assertTrue("$tag must stay vertically inside its container",
            region.top.value >= container.top.value - .5f && region.bottom.value <= container.bottom.value + .5f)
    }
}
