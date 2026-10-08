package com.jiligulu.app.ui.quicktools

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PaymentAppLauncherTest {
    @Test fun alipayIntentsOnlyOpenItsScannerOrPaymentCodeAndContainNoPaymentOrPrivateFilePayload() {
        for (action in PaymentAction.entries) {
            val expected = if (action == PaymentAction.SCAN) "10000007" else "20000056"
            val intents = PaymentAppLauncher.intents(PaymentApp.ALIPAY, action)
            assertEquals(2, intents.size)
            for (intent in intents) {
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("com.eg.android.AlipayGphone", intent.`package`)
                assertNull(intent.component)
                assertEquals("platformapi", intent.data?.host)
                assertEquals("/startapp", intent.data?.path)
                assertEquals(expected, intent.data?.getQueryParameter(if (intent.data?.scheme == "alipayqr") "saId" else "appId"))
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertNull(intent.clipData)
                assertNull(intent.extras)
                assertFalse(intent.data.toString().contains("amount"))
                assertFalse(intent.data.toString().contains("qrcode"))
            }
        }
    }
    @Test fun genericWeChatPathPreservesBothVerifiedLegacyIntentsExactly() {
        for (action in PaymentAction.entries) {
            val original = WeChatLauncher.intent(if (action == PaymentAction.SCAN) WeChatAction.SCAN else WeChatAction.PAY_CODE)
            val generic = PaymentAppLauncher.intents(PaymentApp.WECHAT, action).single()
            assertTrue(original.filterEquals(generic))
            assertEquals(original.flags, generic.flags)
            assertEquals(original.extras!!.keySet(), generic.extras!!.keySet())
            original.extras!!.keySet().forEach { key -> assertEquals(original.extras!!.get(key), generic.extras!!.get(key)) }
        }
    }
    @Test fun aMissingClientIsNotLaunchedAndAnUnavailableSchemeHasALimitedSamePackageFallback() {
        var calls = 0
        assertEquals(PaymentLaunch.MISSING, PaymentAppLauncher.attempt(PaymentApp.ALIPAY, PaymentAction.SCAN, false) { calls++ })
        assertEquals(0, calls)
        val schemes = ArrayList<String>()
        assertEquals(PaymentLaunch.REQUESTED, PaymentAppLauncher.attempt(PaymentApp.ALIPAY, PaymentAction.SCAN, true) {
            schemes += it.data!!.scheme!!
            if (schemes.size == 1) throw ActivityNotFoundException()
        })
        assertEquals(listOf("alipayqr", "alipays"), schemes)
        assertEquals(PaymentLaunch.UNAVAILABLE, PaymentAppLauncher.attempt(PaymentApp.ALIPAY, PaymentAction.PAY_CODE, true) { throw SecurityException() })
    }
}
