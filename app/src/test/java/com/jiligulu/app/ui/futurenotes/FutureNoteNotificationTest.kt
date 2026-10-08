package com.jiligulu.app.ui.futurenotes

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.jiligulu.app.MainActivity
import com.jiligulu.app.data.littleworld.FutureNote
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class FutureNoteNotificationTest {
    @Test fun clickingAReplyNotificationOpensTheExactSavedLetterWithoutReadingIt() {
        val app = RuntimeEnvironment.getApplication()
        val note = FutureNote("reply/with spaces", "阿噜的回信", "只在拆信时展示的正文", 1,
            notificationEnabled = true, sourcePaperId = "paper")
        assertTrue(FutureNoteReminder.notify(app, note))
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(note.id, 1)
        assertEquals("阿噜回信啦", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(note.body))
        notification.contentIntent.send()
        val launch = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, launch.component!!.className)
        assertEquals(note.id, launch.getStringExtra(FutureNoteReminder.EXTRA_ID))
        assertEquals("jiligulu", launch.data!!.scheme)
        assertNull(note.readAt)
    }

    @Test fun collidingJavaHashIdsStillOpenDifferentLetters() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val one = FutureNote("Aa", "第一封", "first", 1, notificationEnabled = true)
        val two = FutureNote("BB", "第二封", "second", 1, notificationEnabled = true)
        FutureNoteReminder.notify(app, one)
        FutureNoteReminder.notify(app, two)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val first = notifications.getNotification(one.id, 1)
        val second = notifications.getNotification(two.id, 1)
        assertNotEquals(first.contentIntent, second.contentIntent)
        first.contentIntent.send()
        assertEquals(one.id, shadowOf(app).nextStartedActivity.getStringExtra(FutureNoteReminder.EXTRA_ID))
        second.contentIntent.send()
        assertEquals(two.id, shadowOf(app).nextStartedActivity.getStringExtra(FutureNoteReminder.EXTRA_ID))
    }

    @Test fun readOrDeleteCancellationOnlyRemovesTheMatchingLetterNotification() {
        val app = RuntimeEnvironment.getApplication()
        val one = FutureNote("one", "第一封", "first", 1, notificationEnabled = true)
        val two = FutureNote("two", "第二封", "second", 1, notificationEnabled = true)
        FutureNoteReminder.notify(app, one)
        FutureNoteReminder.notify(app, two)
        FutureNoteReminder.cancel(app, one.id)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertNull(notifications.getNotification(one.id, 1))
        assertNotNull(notifications.getNotification(two.id, 1))
    }
}
