package com.jiligulu.app.ui.quicktools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

internal enum class PaymentApp(val packageName: String, val title: String) {
    ALIPAY("com.eg.android.AlipayGphone", "支付宝"), WECHAT(WeChatLauncher.PACKAGE, "微信")
}
internal enum class PaymentAction { SCAN, PAY_CODE }
internal enum class PaymentLaunch { REQUESTED, MISSING, UNAVAILABLE }

/** Only opens a client screen. It never reads a code, balance, screen, or payment credentials. */
internal object PaymentAppLauncher {
    fun intents(app: PaymentApp, action: PaymentAction): List<Intent> = when (app) {
        PaymentApp.WECHAT -> listOf(WeChatLauncher.intent(when (action) {
            PaymentAction.SCAN -> WeChatAction.SCAN
            PaymentAction.PAY_CODE -> WeChatAction.PAY_CODE
        }))
        PaymentApp.ALIPAY -> {
            // IDs checked against AlipayZeroSdk's own Android scan/barcode entry points.
            // Keep a limited scheme fallback for client versions with different handlers.
            val id = if (action == PaymentAction.SCAN) "10000007" else "20000056"
            listOf("alipayqr" to "saId", "alipays" to "appId").map { (scheme, key) ->
                Intent(Intent.ACTION_VIEW, Uri.Builder().scheme(scheme).authority("platformapi")
                    .path("/startapp").appendQueryParameter(key, id).build())
                    .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** REQUESTED means Android accepted a launch; client policy may still select another screen. */
    fun attempt(app: PaymentApp, action: PaymentAction, installed: Boolean, launch: (Intent) -> Unit): PaymentLaunch {
        if (!installed) return PaymentLaunch.MISSING
        for (intent in intents(app, action)) {
            try { launch(intent); return PaymentLaunch.REQUESTED }
            catch (_: ActivityNotFoundException) { /* Try the next same-client entry point. */ }
            catch (_: SecurityException) { /* The client may expose a different public scheme. */ }
        }
        return PaymentLaunch.UNAVAILABLE
    }
    fun launch(context: Context, app: PaymentApp, action: PaymentAction): PaymentLaunch {
        val installed = try { context.packageManager.getApplicationInfo(app.packageName, 0).enabled }
            catch (_: PackageManager.NameNotFoundException) { false }
            catch (_: SecurityException) { return PaymentLaunch.UNAVAILABLE }
        return attempt(app, action, installed, context::startActivity)
    }
    fun openApp(context: Context, app: PaymentApp): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) false else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
