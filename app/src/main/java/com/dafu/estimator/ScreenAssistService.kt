package com.dafu.estimator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenAssistService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoReader = object : Runnable {
        override fun run() {
            if (!autoRead) return
            readNow()
            handler.postDelayed(this, 1200)
        }
    }
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 250
        }
        active = this
        log("无障碍服务已连接")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        active = this
        event?.packageName?.toString()?.let { foregroundPackage = it }
    }
    override fun onInterrupt() { log("无障碍服务被系统中断") }
    override fun onDestroy() {
        setAutoRead(false)
        if (active === this) active = null
        log("无障碍服务已断开")
        super.onDestroy()
    }
    fun readNow(): String {
        val root = try { rootInActiveWindow } catch (e: Exception) {
            log("读取窗口失败：" + (e.message ?: e.javaClass.simpleName)); null
        }
        val items = JSONArray()
        val pkg = root?.packageName?.toString() ?: foregroundPackage
        if (root != null) {
            fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
                if (depth > 22 || items.length() >= 300) return
                val label = listOf(node.text?.toString(), node.contentDescription?.toString()).filterNot { it.isNullOrBlank() }.distinct().joinToString(" · ")
                if (label.isNotBlank() || node.isClickable) items.put(JSONObject().put("text", label.take(240)).put("className", node.className?.toString() ?: "").put("clickable", node.isClickable).put("viewId", node.viewIdResourceName ?: "").put("path", path))
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    walk(child, if (path.isEmpty()) "$i" else "$path.$i", depth + 1)
                }
            }
            walk(root, "", 0)
        }
        val result = JSONObject().put("enabled", active != null).put("package", pkg).put("count", items.length()).put("items", items).put("time", System.currentTimeMillis())
        latest = result.toString()
        log("读取完成：包名=$pkg，可访问节点=${items.length()}" + if (items.length() == 0) "；当前界面可能是游戏画布/SurfaceView，未暴露无障碍节点" else "")
        return latest
    }
    fun setAutoRead(enabled: Boolean) {
        autoRead = enabled
        handler.removeCallbacks(autoReader)
        if (enabled) { log("自动读取已开启（间隔 1.2 秒）"); handler.post(autoReader) } else log("自动读取已关闭")
    }
    companion object {
        @Volatile private var active: ScreenAssistService? = null
        @Volatile private var autoRead = false
        @Volatile private var latest = JSONObject().put("enabled", false).toString()
        @Volatile private var foregroundPackage = ""
        private val logs = ArrayDeque<String>()
        private val lock = Any()
        fun log(message: String) {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            synchronized(lock) { logs.addLast("[$stamp] $message"); while (logs.size > 250) logs.removeFirst() }
        }
        fun logsJson(): String = synchronized(lock) { JSONArray(logs.toList()).toString() }
        fun snapshotJson(): String = active?.readNow() ?: JSONObject().put("enabled", false).put("package", foregroundPackage).put("count", 0).put("items", JSONArray()).put("time", System.currentTimeMillis()).toString()
        fun latestJson(): String = latest
        fun setAutoReadEnabled(enabled: Boolean) { active?.setAutoRead(enabled) ?: log("自动读取未启动：请先授权无障碍服务") }
        fun isConnected(): Boolean = active != null
    }
}
