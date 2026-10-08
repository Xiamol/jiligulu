package com.jiligulu.app.ui.quicktools

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class PaymentQuickActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun alipayIsLeftOfWechatAndEveryActionNamesItsActualClient() {
        compose.setContent { MaterialTheme { PaymentQuickActions(Modifier.fillMaxWidth()) } }
        val alipay = compose.onNodeWithTag("payment-group-alipay").fetchSemanticsNode().boundsInRoot
        val wechat = compose.onNodeWithTag("payment-group-wechat").fetchSemanticsNode().boundsInRoot
        assertTrue(alipay.right <= wechat.left)
        for (description in listOf("支付宝扫一扫", "支付宝付款码", "微信扫一扫", "微信付款码"))
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
    }
}
