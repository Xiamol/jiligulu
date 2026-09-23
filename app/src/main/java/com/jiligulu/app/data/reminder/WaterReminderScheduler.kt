package com.jiligulu.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.WorkManager
import androidx.work.await
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single persisted deadline and a single PendingIntent, including the inexact fallback. */
object WaterReminderScheduler {
    const val SCHEDULE_VERSION = 3
    internal const val SLOT_A = "water_reminder_a"
    internal const val SLOT_B = "water_reminder_b"
    internal const val LEGACY_PERIODIC_WORK = "water_reminder"
    internal const val ACTION_FIRE = "com.jiligulu.app.WATER_REMINDER_DUE"
    internal const val EXTRA_DUE_AT = "water_due_at"
    private const val REQUEST_CODE = 4107
    internal val mutex = Mutex()

    internal fun prefs(context: Context): UserPrefs =
        (context.applicationContext as JiliguluApp).container.userPrefs

    /** Settings changes deliberately start a fresh interval. Opening the app uses [restore]. */
    suspend fun schedule(context: Context, intervalMinutes: Int, now: Long = System.currentTimeMillis()) {
        require(intervalMinutes in UserPrefs.MIN_WATER_INTERVAL..UserPrefs.MAX_WATER_INTERVAL)
        mutex.withLock {
            // Preferences are authoritative when two UI/AI writes race before taking this lock.
            val latest = prefs(context).waterReminderState.first()
            armLocked(context, nextDeadline(now, latest.intervalMinutes))
        }
    }

    /** Restores a rebooted/cancelled alarm without continually moving its deadline forward. */
    suspend fun restore(context: Context, now: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        restoreLocked(context, now)
    }

    internal suspend fun restoreLocked(context: Context, now: Long): Boolean {
        val state = prefs(context).waterReminderState.first()
        return when {
            !state.enabled -> {
                cancelAlarm(context)
                prefs(context).clearWaterNextDueAt()
                WaterReminderNotifications.cancelAll(context)
                false
            }
            state.nextDueAt <= 0L -> {
                armLocked(context, nextDeadline(now, state.intervalMinutes))
                false
            }
            state.nextDueAt <= now -> WaterReminderEngine.fireLocked(context, now, catchUp = true)
            else -> {
                postAlarm(context, state.nextDueAt)
                false
            }
        }
    }

    suspend fun cancel(context: Context) = mutex.withLock {
        // A delayed "off" handler must not cancel a newer "on" action from another screen.
        restoreLocked(context, System.currentTimeMillis())
        Unit
    }

    /** One-time migration only. New code never creates any WorkManager reminder jobs. */
    suspend fun cancelLegacyWork(context: Context) {
        val manager = WorkManager.getInstance(context)
        for (name in listOf(LEGACY_PERIODIC_WORK, SLOT_A, SLOT_B)) {
            manager.cancelUniqueWork(name).await()
        }
    }

    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactPermissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    internal fun nextDeadline(now: Long, intervalMinutes: Int): Long =
        now + intervalMinutes.coerceIn(UserPrefs.MIN_WATER_INTERVAL, UserPrefs.MAX_WATER_INTERVAL) * 60_000L

    internal suspend fun armLocked(context: Context, dueAt: Long) {
        if (prefs(context).setWaterNextDueAtIfEnabled(dueAt)) postAlarm(context, dueAt)
        else cancelAlarm(context)
    }

    /** Used after the engine has atomically saved the new deadline and pending cup. */
    internal suspend fun rearmPersistedLocked(context: Context, dueAt: Long) {
        val latest = prefs(context).waterReminderState.first()
        if (latest.enabled && latest.nextDueAt == dueAt) postAlarm(context, dueAt)
        else if (!latest.enabled) cancelAlarm(context)
    }

    private fun operation(context: Context, dueAt: Long = 0L): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, WaterReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_DUE_AT, dueAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
    }

    private fun postAlarm(context: Context, dueAt: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = operation(context, dueAt)
        if (exactAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, operation)
                return
            } catch (_: SecurityException) {
                // Revocation can race canScheduleExactAlarms(). Reuse this same PendingIntent.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, operation)
    }
}
