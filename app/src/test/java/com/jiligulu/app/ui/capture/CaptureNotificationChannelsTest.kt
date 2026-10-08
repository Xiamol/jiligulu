package com.jiligulu.app.ui.capture

import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class CaptureNotificationChannelsTest {
    @Test fun hidingTheBubbleCategoryDoesNotShareTheScreenshotCategory() {
        val context=RuntimeEnvironment.getApplication()
        val bubble=captureNotification(context,"bubble","",FloatingCaptureService::class.java)
        val screenshot=captureNotification(context,"screenshot","",ScreenCaptureService::class.java)
        assertEquals("screen-capture-silent-low",bubble.channelId)
        assertNotEquals(bubble.channelId,screenshot.channelId)
        val manager=context.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(bubble.channelId).sound)
        assertFalse(manager.getNotificationChannel(bubble.channelId).canShowBadge())
    }
    @Test fun theSystemShortcutTargetsOnlyOurExistingFloatingCategory() {
        val context=RuntimeEnvironment.getApplication()
        val intent=CaptureNotificationChannels.floatingSettings(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,intent.action)
        assertEquals(context.packageName,intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(CaptureNotificationChannels.FLOATING,intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
