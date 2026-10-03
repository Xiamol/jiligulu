package com.jiligulu.app.ui.futurenotes

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import com.jiligulu.app.data.littleworld.FutureNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FutureNoteReminder {
    const val EXTRA_ID = "future_note_id"
    private const val CHANNEL = "future_notes"
    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun pending(context: Context, id: String) = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, FutureNoteReceiver::class.java).setAction("com.jiligulu.note.$id").putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, note: FutureNote) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = pending(context, note.id)
        alarm.cancel(intent)
        if (note.notificationEnabled && note.notifiedAt == null && note.readAt == null && hasPermission(context)) {
            // Inexact optional reminders; no new exact-alarm permission is requested.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.dueAt.coerceAtLeast(System.currentTimeMillis() + 1500), intent)
        }
    }
    fun cancel(context: Context, id: String) { context.getSystemService(AlarmManager::class.java).cancel(pending(context,id)) }
    suspend fun restore(context: Context) {
        val repo = (context.applicationContext as JiliguluApp).container.littleWorld
        repo.snapshot().futureNotes.forEach { schedule(context, it) }
    }
    fun notify(context: Context, note: FutureNote): Boolean {
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"未来的小信笺", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, note.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_ID,note.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(note.id, 1, NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_water_notification).setContentTitle("阿噜替你收着的便签到啦 💌")
            .setContentText(note.title).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return true
    }
}

class FutureNoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
                    FutureNoteReminder.restore(context)
                } else {
                    val id = intent.getStringExtra(FutureNoteReminder.EXTRA_ID) ?: return@launch
                    val repo = (context.applicationContext as JiliguluApp).container.littleWorld
                    val note = repo.snapshot().futureNotes.firstOrNull { it.id == id } ?: return@launch
                    if (note.notificationEnabled && note.readAt == null && note.notifiedAt == null && note.dueAt <= System.currentTimeMillis() && FutureNoteReminder.hasPermission(context)) {
                        if (FutureNoteReminder.notify(context,note)) repo.markNoteNotified(id)
                    }
                }
            } catch (_: Exception) { android.util.Log.w("FutureNote", "Reminder could not be delivered; note remains in inbox") } finally { pending.finish() }
        }
    }
}

class FutureNoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { FutureNoteReminder.restore(context) }
            catch (_: Exception) { android.util.Log.w("FutureNote","Reminder recovery will retry on next launch") }
            finally { pending.finish() }
        }
    }
}
