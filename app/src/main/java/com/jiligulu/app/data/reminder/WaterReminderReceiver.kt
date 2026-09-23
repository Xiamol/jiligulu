package com.jiligulu.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class WaterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WaterReminderScheduler.ACTION_FIRE) return
        val dueAt = intent.getLongExtra(WaterReminderScheduler.EXTRA_DUE_AT, 0L)
        if (dueAt <= 0L) return
        runReminderWork {
            WaterReminderEngine.onAlarm(context.applicationContext, expectedDueAt = dueAt)
        }
    }
}

/** Finish goAsync even on failure; no global UI coroutine can be cancelled by this receiver. */
internal fun BroadcastReceiver.runReminderWork(block: suspend () -> Unit) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            withTimeout(8_000L) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The stored deadline remains recoverable by the next foreground launch.
            Log.w("WaterReminder", "Reminder scheduling failed; will recover on next launch")
        } finally {
            pendingResult.finish()
        }
    }
}
