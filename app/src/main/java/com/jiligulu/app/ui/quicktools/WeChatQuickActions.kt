package com.jiligulu.app.ui.quicktools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.GuluDialog

internal enum class WeChatAction { SCAN, PAY_CODE }
internal enum class WeChatLaunch { REQUESTED, MISSING, UNAVAILABLE }

internal object WeChatLauncher {
    const val PACKAGE = "com.tencent.mm"
    fun intent(action: WeChatAction): Intent = Intent(Intent.ACTION_VIEW).apply {
        component = ComponentName(PACKAGE, when (action) {
            WeChatAction.SCAN -> "com.tencent.mm.ui.LauncherUI"
            WeChatAction.PAY_CODE -> "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
        })
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == WeChatAction.SCAN) putExtra("LauncherUI.From.Scaner.Shortcut", true)
        else putExtra("key_entry_scene", 2)
    }

    /** REQUESTED means Android accepted the launch, not that WeChat exposed a particular screen. */
    fun attempt(action: WeChatAction, installed: Boolean, launch: (Intent) -> Unit): WeChatLaunch {
        if (!installed) return WeChatLaunch.MISSING
        return try { launch(intent(action)); WeChatLaunch.REQUESTED }
        catch (_: ActivityNotFoundException) { WeChatLaunch.UNAVAILABLE }
        catch (_: SecurityException) { WeChatLaunch.UNAVAILABLE }
    }

    fun launch(context: Context, action: WeChatAction): WeChatLaunch {
        val installed = try {
            context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) { false }
        return attempt(action, installed, context::startActivity)
    }

    fun openApp(context: Context): Boolean { return try {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
    }
}

@Composable
fun WeChatQuickActions() {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf<WeChatAction?>(null) }
    var missing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(WeChatAction.SCAN to "微信扫一扫", WeChatAction.PAY_CODE to "微信付款码").forEach { (action, label) ->
            TextButton(onClick = {
                when (WeChatLauncher.launch(context, action)) {
                    WeChatLaunch.MISSING -> missing = true
                    WeChatLaunch.UNAVAILABLE -> unavailable = action
                    WeChatLaunch.REQUESTED -> Unit
                }
            }) {
                Icon(if (action == WeChatAction.SCAN) Icons.Outlined.QrCodeScanner else Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (missing) GuluDialog("还没找到微信 ♡", onDismiss = { missing = false }, compact = true) {
        Text("这台设备没有可用的微信，安装或启用后再来试试吧。", style = MaterialTheme.typography.bodyMedium)
    }
    unavailable?.let { action ->
        GuluDialog("这条近路暂时没通 ♡", onDismiss = { unavailable = null }, compact = true,
            dismissLabel = "收好", confirmLabel = "打开微信", onConfirm = {
                unavailable = null
                if (!WeChatLauncher.openApp(context)) missing = true
            }) {
            Text(if (action == WeChatAction.PAY_CODE)
                "这个微信版本没有开放付款码直达。打开微信后，点右上角“＋” → “收付款”就能找到。"
                else "这次没能直接打开扫一扫。打开微信后，点右上角“＋” → “扫一扫”即可。",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
