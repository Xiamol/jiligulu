package com.jiligulu.app.ui.futurenotes

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jiligulu.app.data.littleworld.FutureNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class FutureNoteDeadlineLifecycleTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun anEmptyInboxHasNoRecurringThirtySecondWake() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val times = mutableListOf<Long>()
            val job = launch { owner.lifecycle.watchFutureNoteDeadlines(emptyList(), emptySet(),
                nowMillis = { testScheduler.currentTime }, publish = times::add) }
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            advanceTimeBy(24 * 60 * 60 * 1000L); runCurrent()
            assertEquals(listOf(0L), times)
            owner.registry.currentState = Lifecycle.State.DESTROYED; runCurrent(); job.join()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun onlyActualFutureDeadlinesWakeAndOldLettersNeverBusyLoop() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val times = mutableListOf<Long>()
            val notes = listOf(
                FutureNote(id = "expired", title = "旧信", body = "", dueAt = -1),
                FutureNote(id = "read", title = "已读", body = "", dueAt = 20_000, readAt = 1),
                FutureNote(id = "presented", title = "展示过", body = "", dueAt = 40_000, presentedAt = 1),
                FutureNote(id = "handled", title = "本次收过", body = "", dueAt = 60_000),
                FutureNote(id = "first", title = "第一封", body = "", dueAt = 90_000),
                FutureNote(id = "second", title = "第二封", body = "", dueAt = 180_000))
            val job = launch { owner.lifecycle.watchFutureNoteDeadlines(notes, setOf("handled"),
                nowMillis = { testScheduler.currentTime }, publish = times::add) }
            owner.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            advanceTimeBy(89_999); runCurrent(); assertEquals(listOf(0L), times)
            advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 90_000L), times)
            advanceTimeBy(90_000); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listOf(0L, 90_000L, 180_000L), times)
            owner.registry.currentState = Lifecycle.State.DESTROYED; runCurrent(); job.join()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun goingToBackgroundCancelsTheDeadlineAndReturningPublishesOverdueTime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val times = mutableListOf<Long>()
            val note = FutureNote(id = "due", title = "来信", body = "", dueAt = 90_000)
            val job = launch { owner.lifecycle.watchFutureNoteDeadlines(listOf(note), emptySet(),
                nowMillis = { testScheduler.currentTime }, publish = times::add) }
            owner.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            advanceTimeBy(30_000); runCurrent()
            owner.registry.currentState = Lifecycle.State.CREATED; runCurrent()
            advanceTimeBy(100_000); runCurrent(); assertEquals(listOf(0L), times)
            owner.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            assertEquals(listOf(0L, 130_000L), times)
            advanceTimeBy(30_000); runCurrent(); assertEquals(listOf(0L, 130_000L), times)
            owner.registry.currentState = Lifecycle.State.DESTROYED; runCurrent(); job.join()
        } finally { Dispatchers.resetMain() }
    }
}
