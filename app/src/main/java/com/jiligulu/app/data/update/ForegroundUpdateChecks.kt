package com.jiligulu.app.data.update

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

/** Run once on real foreground entry, including a warm return, not on each recomposition. */
internal suspend fun Lifecycle.checkUpdatesOnForeground(check: suspend () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        check()
        awaitCancellation()
    }
}
