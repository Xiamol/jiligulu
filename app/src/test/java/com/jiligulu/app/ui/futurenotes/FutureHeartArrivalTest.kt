package com.jiligulu.app.ui.futurenotes

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.FutureNote
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FutureHeartArrivalTest {
    @get:Rule val compose = createComposeRule()
    private val app: JiliguluApp get() = RuntimeEnvironment.getApplication()
    private val repo get() = app.container.littleWorld
    private val privateBody = "不应自动曝光的回信正文，只有我主动拆信后才展示。"

    private fun seedNote(): FutureNote = runBlocking {
        repo.snapshot().futureNotes.forEach { repo.deleteFutureNote(it.id) }
        FutureNote(id = "synthetic-heart-arrival", title = "只在邮局打开的回信",
            body = privateBody, dueAt = 1L, sourcePaperId = "synthetic-source-paper")
            .also { repo.saveFutureNote(it) }
    }

    private fun saved(id: String): FutureNote = runBlocking {
        repo.snapshot().futureNotes.single { it.id == id }
    }

    // Exercise the production semantic onClick callbacks. Native pointer delivery is not
    // covered here; separate dialog and Compose clocks both need a frame after an action.
    private fun SemanticsNodeInteraction.activate() =
        performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        val target = SystemClock.uptimeMillis() + 32
        compose.mainClock.advanceTimeBy(32)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((target - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000L) {
            frame()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitArrival() {
        awaitText("阿噜回信啦")
        compose.onNodeWithText("阿噜回信啦").assertIsDisplayed()
        compose.onNodeWithText("去邮局").assertIsDisplayed()
        compose.onNodeWithTag("heart-letter-arrival").assertIsDisplayed()
        assertBodyHidden()
    }

    private fun assertBodyHidden() {
        compose.onNodeWithTag("future-note-body").assertDoesNotExist()
        compose.onNodeWithText(privateBody).assertDoesNotExist()
    }

    @Test fun automaticArrivalShowsOnlyAnInvitationAndTheRealMailboxRequiresManualOpening() {
        val note = seedNote()
        var mailbox by mutableStateOf(false)
        var opens = 0
        var consumed = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            if (mailbox) FutureNotesScreen(onBack = { mailbox = false }) else Text("主页面仍在")
            DueFutureNoteHost(enabled = !mailbox, requestedId = null,
                onConsumed = { consumed++ }, onOpenMailbox = { opens++; mailbox = true })
        } } }
        compose.mainClock.autoAdvance = false

        awaitArrival()
        assertNull(saved(note.id).readAt)
        compose.onNodeWithText(note.title).assertDoesNotExist()
        compose.onNodeWithText("去邮局").activate()

        awaitText("未来邮局")
        compose.onNodeWithText("收件箱").assertIsDisplayed()
        compose.onNodeWithText("今天的收件箱").assertDoesNotExist()
        compose.onNodeWithText(note.title).assertDoesNotExist()
        assertBodyHidden()
        assertEquals(1, opens)
        assertEquals(0, consumed)
        assertNull(saved(note.id).readAt)

        compose.onNodeWithText("收件箱").activate()
        awaitText("今天的收件箱")
        awaitText(note.title)
        compose.onNodeWithTag("future-note-body").assertDoesNotExist()
        assertNull(saved(note.id).readAt)
        compose.onNodeWithText(note.title).activate()
        compose.waitUntil(10_000L) {
            frame()
            compose.onAllNodesWithTag("future-note-body").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("future-note-body").assertIsDisplayed()
        compose.onNodeWithText("标记已读").assertIsDisplayed()
        assertNull(saved(note.id).readAt)
    }

    @Test fun outsideDismissalLeavesTheLetterUnreadAndItsPresentedStatePreventsAutomaticReopening() {
        val note = seedNote()
        var hostMounted by mutableStateOf(true)
        var opens = 0
        var consumed = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            Text("主页面仍在")
            if (hostMounted) DueFutureNoteHost(enabled = true, requestedId = null,
                onConsumed = { consumed++ }, onOpenMailbox = { opens++ })
        } } }
        compose.mainClock.autoAdvance = false
        awaitArrival()

        val dialog = compose.runOnIdle {
            val current = checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing })
            val now = SystemClock.uptimeMillis()
            val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { assertTrue(current.onTouchEvent(outside)) } finally { outside.recycle() }
            current
        }
        compose.waitUntil(10_000L) {
            frame()
            !dialog.isShowing && saved(note.id).presentedAt != null &&
                compose.onAllNodesWithTag("heart-letter-arrival").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("主页面仍在").assertIsDisplayed()
        assertNull(saved(note.id).readAt)
        assertNotNull(saved(note.id).presentedAt)
        assertBodyHidden()
        assertEquals(0, opens)
        assertEquals(0, consumed)

        // Remounting loses the host's in-memory handled set. The stored presented marker
        // must still prevent this same unread letter from being delivered automatically.
        compose.runOnIdle { hostMounted = false }
        frame()
        compose.runOnIdle { hostMounted = true }
        repeat(6) { frame() }
        compose.onNodeWithText("阿噜回信啦").assertDoesNotExist()
        compose.onNodeWithText("去邮局").assertDoesNotExist()
        assertBodyHidden()
        assertNull(saved(note.id).readAt)
        assertEquals(note.body, saved(note.id).body)
        assertEquals(0, opens)
    }
}
