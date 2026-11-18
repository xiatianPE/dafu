package com.dafu.estimator

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class RegionSelectionService : Service() {
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var selector: SelectorView? = null
    private var selection: RectF? = null
    override fun onBind(intent: android.content.Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (root == null) showSelector()
        return START_NOT_STICKY
    }
    private fun startForegroundCompat() {
        val channelId = "dafu_region_selector"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "达芙区域框选", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        notification.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("达芙正在框选识别区域")
            .setContentText("拖动屏幕选择拍卖情报卡区域")
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(22, notification.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(22, notification.build())
    }
    private fun showSelector() {
        val frame = FrameLayout(this)
        val selectView = SelectorView()
        selector = selectView
        frame.addView(selectView, FrameLayout.LayoutParams(-1, -1))
        val top = TextView(this).apply {
            text = "拖动框选拍卖情报卡区域"; textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = pill(0xE61B2250.toInt())
        }
        frame.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = pill(0xF2FFFFFF.toInt())
        }
        fun action(text: String, color: Int, click: () -> Unit) {
            val b = Button(this).apply {
                this.text = text; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
                background = pill(color); setOnClickListener { click() }
            }
            actions.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        action("重选", 0xFF68708B.toInt()) { selection = null; selectView.invalidate() }
        action("取消", 0xFF8B93A8.toInt()) { stopSelf() }
        action("保存区域", 0xFFD99A14.toInt()) { saveSelection() }
        frame.addView(actions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(28)
        })
        val metrics = resources.displayMetrics
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(metrics.widthPixels, metrics.heightPixels, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        try { wm.addView(frame, lp); root = frame }
        catch (e: Exception) {
            NativeLog.add("区域框选启动失败：" + (e.message ?: e.javaClass.simpleName))
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show(); stopSelf()
        }
    }
    private fun saveSelection() {
        val rect = selection; val view = selector
        if (rect == null || view == null || rect.width() < dp(80) || rect.height() < dp(60)) {
            Toast.makeText(this, "请先拖动框选有效区域", Toast.LENGTH_SHORT).show(); return
        }
        val w = view.width.toFloat().coerceAtLeast(1f); val h = view.height.toFloat().coerceAtLeast(1f)
        val l = (rect.left / w).coerceIn(0f, 1f); val t = (rect.top / h).coerceIn(0f, 1f)
        val r = (rect.right / w).coerceIn(0f, 1f); val b = (rect.bottom / h).coerceIn(0f, 1f)
        Store.set("dafu-ocr-region", JSONObject().put("left", l).put("top", t).put("right", r).put("bottom", b)
            .put("updatedAt", System.currentTimeMillis()).toString())
        NativeLog.add("已保存手动 OCR 区域：${(l*100).toInt()}%,${(t*100).toInt()}% - ${(r*100).toInt()}%,${(b*100).toInt()}%")
        Toast.makeText(this, "识别区域已保存", Toast.LENGTH_SHORT).show(); stopSelf()
    }
    private inner class SelectorView : View(this@RegionSelectionService) {
        private val shade = Paint().apply { color = 0x99000000.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC247.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFC247 }
        private var sx = 0f; private var sy = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            selection?.let {
                canvas.drawRect(it, fill); canvas.drawRect(it, border)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13).toFloat(); typeface = Typeface.DEFAULT_BOLD }
                canvas.drawText("${it.width().toInt()} × ${it.height().toInt()}", it.left + dp(8), max(dp(22).toFloat(), it.top - dp(8)), p)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.x; sy = event.y; selection = RectF(sx, sy, sx, sy); invalidate(); return true }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    selection = RectF(min(sx,event.x), min(sy,event.y), max(sx,event.x), max(sy,event.y)); invalidate(); return true
                }
            }
            return true
        }
    }
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        root?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        root = null; selector = null
        stopForeground(true)
        super.onDestroy()
    }
}
