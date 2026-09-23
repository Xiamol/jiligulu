package com.jiligulu.app.data.reminder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundReminderRecoveryTest {
    @Test fun restoresOnReturnAndRetriesWithoutRunningAfterTheScreenLeaves() = runTest {
        var calls = 0
        val job = launch { recoverRemindersWhileVisible { calls++; if (calls == 1) error("platform busy") } }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, calls)
        job.cancel(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)
        val resumed = launch { recoverRemindersWhileVisible { calls++ } }
        runCurrent()
        assertEquals(3, calls)
        resumed.cancel()
    }

    @Test fun optionalRecoveryTimeoutDoesNotKillTheNextAttempt() = runTest {
        var calls = 0
        val job = launch { recoverRemindersWhileVisible { calls++; if (calls == 1) awaitCancellation() } }
        advanceTimeBy(13_001); runCurrent()
        assertEquals(2, calls)
        job.cancel()
    }
}
