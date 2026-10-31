package com.dafu.estimator

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

class OverlayService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL = "dafu_native_overlay"
        private const val NOTIFICATION_ID = 21
        private const val ACTION_STOP = "com.dafu.estimator.OVERLAY_STOP"
    }

    private lateinit var wm: WindowManager
    private var panel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshContent()
            handler.postDelayed(this, 800)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        if (panel == null) {
            running = true
            createNativePanel()
            handler.post(refresh)
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "达芙原生估价悬浮窗", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, NativeControlActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("达芙原生悬浮窗运行中")
            .setContentText("显示当前估价与建议出价")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "关闭", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun createNativePanel() {
        val view = TextView(this).apply {
            text = "达芙估价\n当前估价：等待数据\n建议出价：等待数据"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE9222932.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFB18A48.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            dp(220), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }
        view.setOnTouchListener(dragListener(view, lp))
        view.setOnClickListener {
            try { startActivity(Intent(this, NativeControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        panel = view
        params = lp
        try { wm.addView(view, lp) } catch (_: Exception) { stopSelf() }
    }

    private fun refreshContent() {
        val raw = Store.get("dafu-auction-live") ?: return
        val data = try { JSONObject(raw) } catch (_: Exception) { return }
        val estimate = format(data.optDouble("valuationWan", Double.NaN))
        val bid = format(data.optDouble("suggestedBidWan", Double.NaN))
        panel?.text = "达芙估价  ·  拖动调整位置\n当前估价：$estimate\n建议出价：$bid"
    }

    private fun format(v: Double) = if (v.isNaN() || v.isInfinite()) "等待数据" else String.format("%.2f 万", v)

    private fun dragListener(view: View, lp: WindowManager.LayoutParams): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx=event.rawX; sy=event.rawY; ox=lp.x; oy=lp.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx=event.rawX-sx; val dy=event.rawY-sy
                    if (kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) moved=true
                    if (moved) {
                        val dm=resources.displayMetrics
                        lp.x=(ox+dx.toInt()).coerceIn(0,max(0,dm.widthPixels-view.width))
                        lp.y=(oy+dy.toInt()).coerceIn(0,max(0,dm.heightPixels-view.height))
                        try { wm.updateViewLayout(view,lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                else -> false
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(refresh)
        panel?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        panel = null
        super.onDestroy()
    }
}