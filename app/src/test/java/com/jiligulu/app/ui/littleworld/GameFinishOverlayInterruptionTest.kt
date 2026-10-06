package com.jiligulu.app.ui.littleworld

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameFinishOverlayInterruptionTest {
    @Test fun pauseAndResumeWithoutAFrameCannotReopenTheOldQuestion() = runTest {
        val interruption = GameFinishInterruption()
        var resumed = true
        var questions = 0
        val presentation = launch {
            val job = currentCoroutineContext().job
            val ticket = interruption.attach(job, 0) ?: return@launch
            try {
                delay(GAME_FINISH_HOLD_MS)
                if (interruption.owns(ticket, job) && resumed) questions++
            } finally { interruption.release(job) }
        }
        runCurrent()
        advanceTimeBy(GAME_FINISH_HOLD_MS / 2)
        // Lifecycle callbacks arrive back-to-back. There is no frame, recomposition, or scheduler turn.
        resumed = false
        val interruptedGeneration = interruption.interrupt()
        resumed = true
        assertTrue(presentation.isCancelled)
        advanceTimeBy(GAME_FINISH_HOLD_MS + GAME_FINISH_FADE_MS)
        runCurrent()
        assertEquals(0, questions)

        // A genuinely new request after return can still use the new generation.
        val next = launch {
            val job = currentCoroutineContext().job
            val ticket = interruption.attach(job, interruptedGeneration) ?: return@launch
            try {
                delay(GAME_FINISH_HOLD_MS)
                if (interruption.owns(ticket, job) && resumed) questions++
            } finally { interruption.release(job) }
        }
        runCurrent()
        advanceTimeBy(GAME_FINISH_HOLD_MS)
        runCurrent()
        next.join()
        assertEquals(1, questions)
    }

    @Test fun anEffectScheduledBeforePauseCannotAttachAfterResume() = runTest {
        val interruption = GameFinishInterruption()
        var questions = 0
        val scheduledBeforePause = launch {
            val job = currentCoroutineContext().job
            val ticket = interruption.attach(job, 0) ?: return@launch
            delay(GAME_FINISH_HOLD_MS)
            if (interruption.owns(ticket, job)) questions++
        }
        // Interrupt before the old effect body starts; cancelling an attached Job alone is insufficient.
        interruption.interrupt()
        runCurrent()
        advanceTimeBy(GAME_FINISH_HOLD_MS * 2)
        runCurrent()
        scheduledBeforePause.join()
        assertEquals(0, questions)
    }
}
