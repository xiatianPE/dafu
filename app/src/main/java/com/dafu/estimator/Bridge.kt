package com.dafu.estimator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Stable WebView <-> Android API.
 * All methods exposed to JavaScript return JSON/string/boolean synchronously.
 * Bid execution is dry-run by default; submitting must be explicitly requested.
 */
class Bridge(
    private val context: Context,
    webView: WebView? = null
) {
    @Volatile var seen: Long = Store.version
    @Volatile private var auctionData: String = "{\"schemaVersion\":1}"
    @Volatile private var webRef: WeakReference<WebView>? =
        webView?.let { WeakReference(it) }

    private val main = Handler(Looper.getMainLooper())

    fun bindWebView(webView: WebView) {
        webRef = WeakReference(webView)
    }

    fun stale() = seen != Store.version

    // ---- storage / existing web compatibility ----
    @JavascriptInterface
    fun get(k: String): String? = if (k.startsWith("dafu-")) Store.get(k) else null

    @JavascriptInterface
    fun set(k: String, v: String) {
        if (k.startsWith("dafu-") && v.length <= 1_000_000) {
            Store.set(k, v)
            seen = Store.version
        }
    }

    @JavascriptInterface
    fun remove(k: String) {
        if (k.startsWith("dafu-")) {
            Store.remove(k)
            seen = Store.version
        }
    }

    // ---- permissions / accessibility ----
    @JavascriptInterface
    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface
    fun isAccessibilityEnabled(): Boolean =
        ScreenAssistService.isEnabled(context)

    @JavascriptInterface
    fun isAccessibilityConnected(): Boolean =
        ScreenAssistService.isConnected()

    @JavascriptInterface
    fun openNativeControlPanel() {
        context.startActivity(
            Intent(context, NativeControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface
    fun readScreen(): String = ScreenAssistService.snapshotJson()

    @JavascriptInterface
    fun clickScreenItem(index: Int): Boolean =
        index >= 0 && ScreenAssistService.clickItem(index)

    // ---- screen capture / OCR ----
    @JavascriptInterface
    fun startScreenCapture() {
        ScreenCaptureActivity.launch(context)
    }

    @JavascriptInterface
    fun stopScreenCapture() {
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }

    @JavascriptInterface
    fun isScreenCaptureRunning(): Boolean =
        ScreenCaptureService.running

    @JavascriptInterface
    fun getScreenRecognition(): String =
        ScreenCaptureService.latestJson

    // ---- auto bid ----
    /** price is the in-game integer price unit, not the display value in 万. */
    @JavascriptInterface
    fun requestAutoBid(price: Long): Boolean =
        requestAutoBidInternal(price, submit = false, requireVerify = true)

    /** Explicit opt-in entry point for actual submission. */
    @JavascriptInterface
    fun requestAutoBidSubmit(price: Long): Boolean =
        requestAutoBidInternal(price, submit = true, requireVerify = true)

    @JavascriptInterface
    fun cancelAutoBid() {
        invokeObject("com.dafu.estimator.AutoBidder", "cancel")
    }

    @JavascriptInterface
    fun getAutoBidStatus(): String =
        (invokeObject("com.dafu.estimator.AutoBidder", "statusJson") as? String) ?: JSONObject().put("state", "UNAVAILABLE").put("message", "自动出价模块尚未接入").toString()

    private fun requestAutoBidInternal(
        price: Long,
        submit: Boolean,
        requireVerify: Boolean
    ): Boolean {
        if (price <= 0L || price > 1_000_000_000L) {
            NativeLog.add("自动出价请求被拒绝：价格超出有效范围")
            return false
        }
        if (!ScreenAssistService.isConnected()) {
            NativeLog.add("自动出价请求失败：请先开启无障碍服务")
            return false
        }
        if (!ScreenCaptureService.running) {
            NativeLog.add("自动出价请求失败：请先启动屏幕识别")
            return false
        }
        return (invokeObject(
            "com.dafu.estimator.AutoBidder", "request",
            arrayOf(Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrayOf(price, submit, requireVerify)
        ) as? Boolean) == true
    }

    // ---- game state ----
    @JavascriptInterface
    fun setGameMaxRounds(rounds: Int): Boolean {
        if (rounds !in 0..100) return false
        val tracker = objectInstance("com.dafu.estimator.GameTracker") ?: return false
        tracker.javaClass.getField("maxRounds").setInt(tracker, rounds)
        NativeLog.add("游戏总回合数设置为：${if (rounds == 0) "自动判断" else rounds}")
        return true
    }

    @JavascriptInterface
    fun resetGameTracker() {
        invokeObject("com.dafu.estimator.GameTracker", "reset")
        NativeLog.add("已重置本局游戏状态")
    }

    @JavascriptInterface
    fun getGameState(): String {
        val frame = try { JSONObject(ScreenCaptureService.latestJson) }
        catch (_: Exception) { JSONObject() }
        val game = frame.optJSONObject("game") ?: JSONObject()
        val tracker = objectInstance("com.dafu.estimator.GameTracker")
        val round = (invokeObject("com.dafu.estimator.GameTracker", "currentRound") as? Int) ?: 0
        val ended = (invokeObject("com.dafu.estimator.GameTracker", "isGameOver") as? Boolean) ?: false
        val max = try { tracker?.javaClass?.getField("maxRounds")?.getInt(tracker) ?: 0 } catch (_: Exception) { 0 }
        return JSONObject().put("round", round).put("isGameOver", ended)
            .put("maxRounds", max).put("game", game).toString()
    }

    // ---- overlay ----
    @JavascriptInterface
    fun setOverlayEnabled(enabled: Boolean): String {
        if (!enabled) {
            context.stopService(Intent(context, OverlayService::class.java))
            return "stopped"
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "permission_required"
        }
        return try {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
            "started"
        } catch (e: Exception) {
            "error:" + (e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun isOverlayRunning(): Boolean = OverlayService.running

    // ---- logs ----
    @JavascriptInterface
    fun getNativeLogs(limit: Int): String {
        val safeLimit = limit.coerceIn(1, 80)
        val array = JSONArray()
        NativeLog.snapshot(safeLimit).forEach { array.put(it) }
        return array.toString()
    }

    // ---- auction data sync ----
    @JavascriptInterface
    fun publishAuctionData(json: String): Boolean {
        if (json.length > 20_000) return false
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("schemaVersion") != 1) return false
            auctionData = obj.toString()
            Store.set("dafu-auction-live", auctionData)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun getAuctionData(): String = auctionData


    private fun objectInstance(className: String): Any? = try {
        Class.forName(className).getField("INSTANCE").get(null)
    } catch (_: Exception) { null }

    private fun invokeObject(
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray()
    ): Any? = try {
        val instance = objectInstance(className) ?: return null
        instance.javaClass.getMethod(methodName, *parameterTypes).invoke(instance, *args)
    } catch (e: Exception) {
        NativeLog.add("原生接口调用失败：$methodName")
        null
    }

    // ---- native -> web event channel ----
    fun emit(name: String, json: String = "{}") {
        if (!name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) return
        val safeJson = try { JSONObject(json).toString() } catch (_: Exception) { "{}" }
        main.post {
            val web = webRef?.get() ?: return@post
            web.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('dafu-native', {detail:{name:" +
                    JSONObject.quote(name) + ",data:" + safeJson + "}}));",
                null
            )
        }
    }
}
