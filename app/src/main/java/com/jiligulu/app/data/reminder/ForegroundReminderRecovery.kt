package com.jiligulu.app.data.reminder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Only active while an Activity is visible; never starts a second background reminder chain. */
internal suspend fun recoverRemindersWhileVisible(recover: suspend () -> Unit) {
    while (currentCoroutineContext().isActive) {
        try {
            withTimeoutOrNull(3_000L) { recover() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Platform scheduling can fail transiently; retry while visible instead of breaking the loop.
        }
        delay(10_000L)
    }
}
