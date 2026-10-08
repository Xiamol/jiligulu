package com.jiligulu.app.ui.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.CompanionFact
import com.jiligulu.app.domain.persona.CompanionMemoryPolicy
import com.jiligulu.app.domain.persona.CompanionMemoryState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class CompanionHistoryPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val historicalTime = 1_800_000_000_000L - 7 * 86_400_000L

    private fun fact(kind: String, value: String, evidence: String, time: Long = historicalTime) =
        CompanionFact(CompanionMemoryPolicy.id(kind, value), kind, value, evidence, time)

    private fun readyPrefs(existing: List<CompanionFact> = emptyList()): UserPrefs = runBlocking {
        UserPrefs(context).also { prefs ->
            prefs.clearCompanionMemories()
            prefs.setCompanionMemoryEnabled(true)
            if (existing.isNotEmpty()) {
                val snapshot = prefs.companionMemory.first()
                assertTrue(prefs.rememberCompanionFactsIfCurrent(snapshot.revision, existing))
            }
        }
    }

    private fun memory(prefs: UserPrefs): CompanionMemoryState = runBlocking { prefs.companionMemory.first() }

    private fun show(prefs: UserPrefs, candidates: List<CompanionFact>, scans: AtomicInteger) {
        compose.setContent { MaterialTheme { Column {
            CompanionMemorySettings(enabled = true, prefs = prefs, scanHistory = {
                scans.incrementAndGet()
                candidates
            })
        } } }
        compose.mainClock.autoAdvance = false
        compose.waitUntil(8_000L) {
            dialogFrame()
            compose.onAllNodesWithTag("companion-memory-history").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }

    // Dialog windows have their own recomposer. Advance both clocks after semantic actions.
    // These tests exercise actual UI callbacks and selections, rather than pointer delivery.
    private fun dialogFrame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        val target = SystemClock.uptimeMillis() + 32
        compose.mainClock.advanceTimeBy(32)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((target - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.activate() =
        performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun openPreview() {
        compose.onNodeWithTag("companion-memory-history").activate()
        compose.waitUntil(8_000L) {
            dialogFrame()
            compose.onAllNodesWithText("记下所选").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("补记聊天资料").assertCountEquals(2)
        compose.onNodeWithText("记下所选").assertIsDisplayed()
    }

    private fun checkbox(id: String): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher("has checkbox state") { it.config.contains(SemanticsProperties.ToggleableState) } and
            hasAnyAncestor(hasTestTag("companion-history-$id")),
        useUnmergedTree = true)

    private fun awaitSaved(prefs: UserPrefs, expected: (CompanionMemoryState) -> Boolean) {
        // Never block the paused UI thread on DataStore.first() while its edit is in flight:
        // the transform/continuation may itself need that thread. Observe continuously off it.
        val observed = AtomicReference<CompanionMemoryState?>(null)
        val observerFailure = AtomicReference<String?>(null)
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        observation.launch {
            try { prefs.companionMemory.collect { observed.set(it) } }
            catch (failure: Exception) { observerFailure.set(failure.javaClass.simpleName) }
        }
        val dialog = SemanticsMatcher("is a dialog") { it.config.contains(SemanticsProperties.IsDialog) }
        val changedError = hasText("资料或开关已经变化，请重新预览再确认") and hasAnyAncestor(dialog)
        var factsSaved = false
        var dialogCount = -1
        var confirmCount = -1
        var disabledConfirms = -1
        var busyCount = -1
        var dialogErrorCount = -1
        try {
            compose.waitUntil(8_000L) {
                dialogFrame()
                factsSaved = observed.get()?.let(expected) == true
                dialogCount = compose.onAllNodes(dialog).fetchSemanticsNodes().size
                val confirms = compose.onAllNodesWithText("记下所选").fetchSemanticsNodes()
                confirmCount = confirms.size
                disabledConfirms = confirms.count { it.config.contains(SemanticsProperties.Disabled) }
                busyCount = compose.onAllNodesWithText("正在处理…").fetchSemanticsNodes().size
                dialogErrorCount = compose.onAllNodes(changedError).fetchSemanticsNodes().size
                factsSaved && dialogCount == 0 && confirmCount == 0 && busyCount == 0
            }
        } catch (failure: Throwable) {
            val state = observed.get()
            val metadata = state?.let { snapshot ->
                "enabled=${snapshot.enabled}, revision=${snapshot.revision}, historyVersion=${snapshot.historyLearningVersion}, " +
                    "facts=${snapshot.facts.map { "${it.id}:${it.kind}@${it.updatedAt}" }}"
            } ?: "no preference emission"
            throw AssertionError("Preview confirmation timed out: preferences={$metadata}, expectedFacts=$factsSaved, " +
                "dialogs=$dialogCount, confirms=$confirmCount, disabledConfirms=$disabledConfirms, " +
                "busyLabels=$busyCount, dialogErrors=$dialogErrorCount, observerFailure=${observerFailure.get()}", failure)
        } finally {
            observation.cancel()
        }
    }

    private fun awaitChangedError() {
        val dialogError = hasText("资料或开关已经变化，请重新预览再确认") and
            hasAnyAncestor(SemanticsMatcher("is a dialog") { it.config.contains(SemanticsProperties.IsDialog) })
        compose.waitUntil(8_000L) {
            dialogFrame()
            compose.onAllNodes(dialogError).fetchSemanticsNodes().size == 1
        }
        compose.onNode(dialogError).assertIsDisplayed()
    }

    @Test fun previewDoesNotWriteAndConfirmationStoresOnlyNewSelectedFactsWithHistoricalDates() {
        val study = fact("study", "大学生", "我是大学生")
        val gender = fact("gender", "男", "男生", historicalTime + 1_000L)
        val birthday = fact("birthday", "2月28日", "2月28", historicalTime + 2_000L)
        val prefs = readyPrefs(listOf(study))
        val before = memory(prefs)
        val scans = AtomicInteger()
        show(prefs, listOf(study.copy(updatedAt = historicalTime - 1_000L), gender, birthday), scans)

        openPreview()
        compose.onNodeWithTag("companion-history-${study.id}").assertDoesNotExist()
        checkbox(gender.id).assertIsOn()
        checkbox(birthday.id).assertIsOn()
        assertEquals(before, memory(prefs))
        assertEquals(1, scans.get())

        compose.onNodeWithText("记下所选").assertIsEnabled().activate()
        awaitSaved(prefs) { it.facts.map { fact -> fact.kind }.toSet() == setOf("study", "gender", "birthday") }
        val after = memory(prefs)
        assertEquals(setOf(study, gender, birthday), after.facts.toSet())
        assertEquals(before.revision + 1, after.revision)
        assertEquals(CompanionMemoryPolicy.HISTORY_LEARNING_VERSION, after.historyLearningVersion)
        assertEquals(historicalTime + 1_000L, after.facts.single { it.kind == "gender" }.updatedAt)
        assertEquals(historicalTime + 2_000L, after.facts.single { it.kind == "birthday" }.updatedAt)
        assertEquals(1, scans.get())
    }

    @Test fun aConflictingAgeStartsUncheckedAndOnlyAnExplicitSelectionCanReplaceIt() {
        val current = fact("age", "20岁", "", historicalTime + 10_000L).copy(editedByUser = true)
        val historical = fact("age", "19岁", "19", historicalTime)
        val prefs = readyPrefs(listOf(current))
        val before = memory(prefs)
        val scans = AtomicInteger()
        show(prefs, listOf(historical), scans)

        openPreview()
        compose.onNodeWithText("现在记着：20岁").assertIsDisplayed()
        checkbox(historical.id).assertIsOff()
        compose.onNodeWithText("记下所选").assertIsNotEnabled()
        assertEquals(before, memory(prefs))

        checkbox(historical.id).activate()
        dialogFrame()
        checkbox(historical.id).assertIsOn()
        compose.onNodeWithText("记下所选").assertIsEnabled().activate()
        awaitSaved(prefs) { it.facts.singleOrNull()?.value == "19岁" }
        assertEquals(listOf(historical), memory(prefs).facts)
        assertEquals(before.revision + 1, memory(prefs).revision)
        assertEquals(1, scans.get())
    }

    @Test fun clearingWhileThePreviewIsOpenCannotRestoreItsStaleCandidates() {
        val candidate = fact("gender", "男", "男生")
        val prefs = readyPrefs()
        val scans = AtomicInteger()
        show(prefs, listOf(candidate), scans)
        openPreview()
        checkbox(candidate.id).assertIsOn()

        runBlocking { prefs.clearCompanionMemories() }
        val cleared = memory(prefs)
        compose.onNodeWithText("记下所选").activate()
        awaitChangedError()
        assertEquals(cleared, memory(prefs))
        assertTrue(memory(prefs).facts.isEmpty())
        assertEquals(1, scans.get())
    }

    @Test fun disablingWhileThePreviewIsOpenBlocksEvenAnExplicitConfirmation() {
        val candidate = fact("birthday", "2月28日", "2月28")
        val prefs = readyPrefs()
        val scans = AtomicInteger()
        show(prefs, listOf(candidate), scans)
        openPreview()
        checkbox(candidate.id).assertIsOn()

        runBlocking { prefs.setCompanionMemoryEnabled(false) }
        val disabled = memory(prefs)
        compose.onNodeWithText("记下所选").activate()
        awaitChangedError()
        assertEquals(disabled, memory(prefs))
        assertFalse(memory(prefs).enabled)
        assertTrue(memory(prefs).facts.isEmpty())
        assertEquals(1, scans.get())
    }
}
