package com.jiligulu.app.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppGlassExpiryTest {
    private fun set(name:String,value:Any?) { AppGlassBackdrop::class.java.getDeclaredField(name).apply { isAccessible=true }.set(AppGlassBackdrop,value) }
    private fun get(name:String):Any? = AppGlassBackdrop::class.java.getDeclaredField(name).apply { isAccessible=true }.get(AppGlassBackdrop)
    private fun call(name:String) { AppGlassBackdrop::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(AppGlassBackdrop) }
    @After fun clear() {call("cancelRefresh");call("forgetPublishedFrame")}
    private fun sample(changing:Boolean):Bitmap {
        val now=SystemClock.uptimeMillis()
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        set("epoch",3L);set("committedFrame",if(changing)101L else 100L);set("targetFps",120)
        set("publishedTicket",OwnGlassFrameTicket(3,100,now,now));set("publishedBitmap",bitmap)
        set("publishedSourceChangedAt",if(changing)now else null)
        call("armExpiry")
        return bitmap
    }
    @Test fun exhaustedCopyRetriesDoNotKeepTheLastMovingImageForeverAfterScrollingStops() {
        val held=sample(changing=true)
        assertNotNull(get("expiryTask"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertNull(get("publishedBitmap"));assertNull(get("publishedTicket"));assertNull(get("expiryTask"))
        assertFalse("Already submitted pixels must never be recycled",held.isRecycled)
    }
    @Test fun aStaticImageHasNoExpiryTimerOrPeriodicInvalidate() {
        val held=sample(changing=false)
        assertNull(get("expiryTask"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1))
        assertSame(held,get("publishedBitmap"));assertNull(get("expiryTask"))
    }
}
