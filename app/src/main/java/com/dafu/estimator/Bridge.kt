package com.dafu.estimator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

class Bridge(private val context: Context) {
    @Volatile var seen: Long = Store.version
    @Volatile private var auctionData: String = "{\"schemaVersion\":1}"
    fun stale() = seen != Store.version
    @JavascriptInterface fun get(k: String): String? = if (k.startsWith("dafu-")) Store.get(k) else null
    @JavascriptInterface fun set(k: String, v: String) { if (k.startsWith("dafu-") && v.length <= 1000000) { Store.set(k, v); seen = Store.version } }
    @JavascriptInterface fun remove(k: String) { if (k.startsWith("dafu-")) { Store.remove(k); seen = Store.version } }
    @JavascriptInterface fun openDebugPanel() {
        context.startActivity(Intent(context, DebugActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @JavascriptInterface fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @JavascriptInterface fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    @JavascriptInterface fun getPermissionStatus(): String = DebugActivity.permissionStatus(context).toString()
    @JavascriptInterface fun readScreen(): String = ScreenAssistService.snapshotJson()
    @JavascriptInterface fun getDebugLogs(): String = ScreenAssistService.logsJson()
    @JavascriptInterface fun setAutoRead(enabled: Boolean) { ScreenAssistService.setAutoReadEnabled(enabled) }
    @JavascriptInterface fun setOverlayEnabled(enabled: Boolean): String {
        if (!enabled) { context.stopService(Intent(context, OverlayService::class.java)); return "stopped" }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            openOverlaySettings(); return "permission_required"
        }
        return try {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            "started"
        } catch (e: Exception) { "error:" + e.message }
    }
    @JavascriptInterface fun publishAuctionData(json: String): Boolean {
        if (json.length > 20000) return false
        return try { val obj = JSONObject(json); if (obj.optInt("schemaVersion") != 1) false else { auctionData = obj.toString(); true } } catch (_: Exception) { false }
    }
    @JavascriptInterface fun getAuctionData(): String = auctionData
}
