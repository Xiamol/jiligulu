package com.jiligulu.app.ui.quicktools

import android.app.Application
import android.content.ActivityNotFoundException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WeChatLauncherTest {
    @Test fun scanTargetsOnlyWeChatAndUsesShortcutFlag() {
        val result = WeChatLauncher.attempt(WeChatAction.SCAN, true) {
            assertEquals("com.tencent.mm", it.component?.packageName)
            assertTrue(it.getBooleanExtra("LauncherUI.From.Scaner.Shortcut", false))
        }
        assertEquals(WeChatLaunch.REQUESTED, result)
    }
    @Test fun inaccessiblePaymentIsReportedInsteadOfClaimingSuccess() {
        assertEquals(WeChatLaunch.UNAVAILABLE, WeChatLauncher.attempt(WeChatAction.PAY_CODE, true) { throw SecurityException() })
        assertEquals(WeChatLaunch.UNAVAILABLE, WeChatLauncher.attempt(WeChatAction.PAY_CODE, true) { throw ActivityNotFoundException() })
    }
    @Test fun missingWeChatDoesNotAttemptAnotherApp() {
        var called = false
        assertEquals(WeChatLaunch.MISSING, WeChatLauncher.attempt(WeChatAction.SCAN, false) { called = true })
        assertFalse(called)
    }
}
