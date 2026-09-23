package com.jiligulu.app.data.reminder

import android.content.Context
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.PersonaEventBus
import com.jiligulu.app.domain.persona.QuipLibrary
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

object WaterReminderEngine {
    /** A stale system alarm can repair a settings write whose OS reschedule was interrupted. */
    suspend fun onAlarm(
        context: Context,
        expectedDueAt: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean = WaterReminderScheduler.mutex.withLock {
        if (fireLocked(context, now, catchUp = false, expectedDueAt = expectedDueAt)) true
        else WaterReminderScheduler.restoreLocked(context, now)
    }

    /** Duplicate broadcasts and cold-start recovery consume the same persisted deadline. */
    suspend fun fire(
        context: Context,
        now: Long = System.currentTimeMillis(),
        catchUp: Boolean = false,
        expectedDueAt: Long? = null
    ): Boolean = WaterReminderScheduler.mutex.withLock {
        fireLocked(context, now, catchUp, expectedDueAt)
    }

    suspend fun catchUpIfMissed(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        WaterReminderScheduler.restore(context, now)

    internal suspend fun fireLocked(
        context: Context, now: Long, catchUp: Boolean, expectedDueAt: Long? = null
    ): Boolean {
        val prefs = WaterReminderScheduler.prefs(context)
        val state = prefs.waterReminderState.first()
        if (!state.enabled || state.nextDueAt <= 0L || now < state.nextDueAt ||
            (expectedDueAt != null && state.nextDueAt != expectedDueAt)) return false

        val engine = PersonaEngine(QuipLibrary.get(context))
        val shouldDeliver = !engine.inQuietHours(now, state.quietStartMinutes, state.quietEndMinutes) &&
            !(catchUp && state.pending.isPending)
        val text = if (shouldDeliver) {
            val nickname = prefs.nickname.first()
            val name = if (nickname.isBlank()) "" else nickname + prefs.nameSuffix.first()
            engine.nextWaterQuipForNotification(name)
        } else null

        // One DataStore transaction consumes the deadline and persists the cup. An exception or
        // process death between saving and Android delivery cannot re-consume this same deadline.
        val advanced = prefs.advanceWaterDeadline(state.nextDueAt, now, text, skipExistingCup = catchUp)
            ?: return false
        val pending = advanced.pendingToDeliver
        try {
            WaterReminderScheduler.rearmPersistedLocked(context, advanced.nextDueAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The next deadline is saved before posting the alarm. Opening the app will re-arm it;
            // a scheduler failure must not suppress the cup/notification that is already due now.
            Log.w("WaterReminder", "Could not arm next alarm; retained deadline for recovery")
        }
        val beforeDelivery = prefs.waterReminderState.first()
        if (pending == null || !pending.isPending || !beforeDelivery.enabled ||
            beforeDelivery.pending.id != pending.id) return false

        val app = context.applicationContext as JiliguluApp
        if (app.isForeground && PersonaEventBus.isHostVisible) {
            // PersonaViewModel observes the persisted cup directly, even without an event subscriber.
            PersonaEventBus.emit(PersonaEventBus.Event.WaterTick)
        } else {
            try {
                WaterReminderNotifications.post(context, pending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The pending cup and next deadline were saved, so notification failure cannot
                // drop the waiting pose or break the future reminder chain.
                Log.w("WaterReminder", "Could not post notification; retained pending cup")
            }
        }
        return true
    }
}
