package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class XiaoLiuRenPaneTest {
    @get:Rule val compose = createComposeRule()
    private val owner = SupervisorJob()
    private fun store() = XiaoLiuRenStore(RuntimeEnvironment.getApplication().getSharedPreferences(
        "liuren-pane-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    @After fun close() { owner.cancel() }

    @Test fun asksQuestionBeforeChoosingMethodAndRejectsIncompleteDigitsWithoutLosingInput() {
        val store = store()
        var requests = 0
        val repo = XiaoLiuRenAnalysisRepository(store, CoroutineScope(owner + Dispatchers.Unconfined)) {
            requests++; error("synthetic provider unavailable")
        }
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { XiaoLiuRenPane(store, repository = repo) } } }
        compose.onNodeWithText("你想问哪一件小事呀？").assertExists()
        compose.onNodeWithTag("liuren-mode-TIME").assertDoesNotExist()
        compose.onNodeWithTag("liuren-question").performTextReplacement("明天面试，怎样准备？")
        compose.onNodeWithTag("liuren-next").performClick()
        compose.onNodeWithTag("liuren-mode-NUMBERS").performClick()
        compose.onNodeWithTag("liuren-digits").performTextReplacement("01")
        compose.onNodeWithTag("liuren-cast").performClick()
        compose.onNodeWithText("写三个 0–9 的数字就好，比如 137").assertExists()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("liuren-digits").performTextReplacement("012")
        compose.onNodeWithTag("liuren-cast").performClick()
        compose.onNodeWithTag("liuren-result").assertExists()
        compose.onNodeWithTag("liuren-snapshot").assertExists()
        compose.runOnIdle {
            val snapshot = requireNotNull(store.session().cast)
            assertEquals("明天面试，怎样准备？", snapshot.question)
            assertEquals(listOf(10, 1, 2), snapshot.counts)
            assertEquals(LiuRenPalace.XIAO_JI, snapshot.result.hour)
            assertEquals(1, requests)
        }
    }

    @Test fun closingAndReopeningAnUnfinishedQuestionRestoresItWithoutAutomaticAnalysis() {
        val store = store()
        var requests = 0
        val repo = XiaoLiuRenAnalysisRepository(store, CoroutineScope(owner + Dispatchers.Unconfined)) {
            requests++; error("merely opening a page must not create a request")
        }
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible) XiaoLiuRenPane(store, repository = repo) } }
        compose.onNodeWithTag("liuren-question").performTextReplacement("我还没想好，先记到这里")
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("我还没想好，先记到这里", store.session().question)
            assertEquals(0, requests)
            visible = true
        }
        compose.onNodeWithText("我还没想好，先记到这里").assertExists()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun rewritingChangesTheMainAnswerWithoutCallingApiAndKeepsOriginalDetailsAvailable() {
        val saved = store()
        val cast = LiuRenCast("我今年能谈到女朋友吗？桃花如何？", LiuRenMode.NUMBERS,
            Instant.parse("2026-10-09T06:00:00Z").toEpochMilli(), "Asia/Shanghai", 8, 29, 8, digits = "840")
        saved.saveSession(LiuRenSession(cast.question, cast.mode, cast.digits, LiuRenStep.RESULT, cast))
        var calls = 0
        val repo = XiaoLiuRenAnalysisRepository(saved, CoroutineScope(owner + Dispatchers.Unconfined)) { calls++; error("not requested") }
        var state by mutableStateOf(LiuRenRewriteState())
        compose.setContent { MaterialTheme { XiaoLiuRenPane(saved, { state }, { current ->
            state = LiuRenRewriteState(LiuRenRewrite.receipt(current, LocalDate.of(2026, 10, 9).toString(), cast.capturedAtMillis), available = false)
        }, repo) } }
        compose.onNodeWithTag("liuren-stage-0").assertDoesNotExist()
        compose.onNodeWithTag("liuren-summary").assertExists()
        compose.onNodeWithTag("liuren-rewrite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("liuren-rewrite-answer").assertExists()
        compose.onNodeWithTag("liuren-summary").assertDoesNotExist()
        compose.onNodeWithTag("liuren-original-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("liuren-summary").assertExists()
        compose.onNodeWithTag("liuren-details-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("liuren-stage-0").assertExists()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(cast, saved.session().cast) }
    }
}
