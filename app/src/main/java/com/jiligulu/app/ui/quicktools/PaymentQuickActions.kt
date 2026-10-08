package com.jiligulu.app.ui.quicktools

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.core.audio.UiSound

/** Two compact client groups, Alipay on the left. Every control names its app for accessibility. */
@Composable
fun PaymentQuickActions(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf<Pair<PaymentApp, PaymentAction>?>(null) }
    fun missing(app: PaymentApp) { Toast.makeText(context, "没有可用的${app.title}，安装或启用后再试", Toast.LENGTH_SHORT).show() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PaymentApp.entries.forEach { app ->
            val tint = if (app == PaymentApp.ALIPAY) Color(0xFF718FA9) else Color(0xFF759580)
            BoxWithConstraints(Modifier.weight(1f).testTag("payment-group-${app.name.lowercase()}")) {
            val showIcons = maxWidth >= 138.dp
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(app.title, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                PaymentAction.entries.forEach { action ->
                    Surface(Modifier.weight(1f), color = tint.copy(alpha = .06f), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.clickable(role = Role.Button) {
                            UiSound.navigate(context)
                            when (PaymentAppLauncher.launch(context, app, action)) {
                                PaymentLaunch.REQUESTED -> Unit
                                PaymentLaunch.MISSING -> missing(app)
                                PaymentLaunch.UNAVAILABLE -> unavailable = app to action
                            }
                        }.semantics { contentDescription = app.title + if (action == PaymentAction.SCAN) "扫一扫" else "付款码" }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (showIcons) Icon(if (action == PaymentAction.SCAN) Icons.Outlined.QrCodeScanner else Icons.Outlined.AccountBalanceWallet,
                                null, Modifier.size(13.dp), tint = tint)
                            Text(if (action == PaymentAction.SCAN) "扫码" else "付款", style = MaterialTheme.typography.labelSmall,
                                color = tint, maxLines = 1)
                        }
                    }
                }
            }
            }
        }
    }
    unavailable?.let { (app, action) ->
        GuluDialog("这条近路暂时没通", onDismiss = { unavailable = null }, compact = true, dense = true,
            confirmLabel = "打开${app.title}", onConfirm = {
                unavailable = null
                if (!PaymentAppLauncher.openApp(context, app)) missing(app)
            }) {
            Text(when (app) {
                PaymentApp.ALIPAY -> if (action == PaymentAction.SCAN) "可以打开支付宝，再点首页的“扫一扫”。"
                    else "可以打开支付宝，再点首页的“收付款”。"
                PaymentApp.WECHAT -> if (action == PaymentAction.SCAN) "可以打开微信，点右上角“＋”里的“扫一扫”。"
                    else "可以打开微信，点右上角“＋”里的“收付款”。"
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
