package com.dafu.estimator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮窗：一个可拖动的小圆球，点开后是一个装着 dafu.html 的面板（可拖动、可最小化、可关闭）。
 * 数据与主界面共用 Store；面板重新显示时，如果数据被别处改过会自动刷新。
 * 说明：只显示/计算，所有输入由你手动完成，没有任何自动读取或点击游戏界面的功能。
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CH = "dafu_overlay"
        private const val NID = 1
        private const val ACTION_STOP = "com.dafu.estimator.STOP"
    }

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var web: WebView? = null
    private var bridge: Bridge? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var bubbleOn = false
    private var panelOn = false
    private var loaded = false
    private var panelPlaced = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (bubble == null) {
            running = true
            buildBubble()
            buildPanel()
            showBubble()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        removeBubble()
        removePanel()
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    // ---------- 前台通知 ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "估价悬浮窗", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("达芙估价悬浮窗运行中")
            .setContentText("点击打开应用，或点下方按钮关闭")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "关闭悬浮窗", stopPi
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }

    // ---------- 窗口参数 ----------

    private fun baseLp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------- 圆球 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = dp(40)
        val tv = TextView(this).apply {
            text = "估"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99D9A441.toInt())
                setStroke(dp(2), 0x55FFFFFF)
            }
            elevation = dp(4).toFloat()
        }
        val lp = baseLp(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }
        tv.setOnTouchListener(dragger(tv, lp) { showPanel() })
        bubble = tv
        bubbleLp = lp
    }

    // ---------- 面板 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val p = object : LinearLayout(this) {
            // 返回键 = 最小化成圆球
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) showBubble()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        p.orientation = LinearLayout.VERTICAL
        p.background = GradientDrawable().apply {
            setColor(0xFF1B1B1F.toInt())
            cornerRadius = dp(14).toFloat()
        }
        p.clipToOutline = true

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2A30.toInt())
            setPadding(dp(12), 0, dp(4), 0)
        }
        val title = TextView(this).apply {
            text = "达芙估价 · 按住此处拖动"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }
        head.addView(title, LinearLayout.LayoutParams(0, match, 1f))
        head.addView(headBtn("—") { showBubble() }, LinearLayout.LayoutParams(dp(48), match))
        head.addView(headBtn("✕") { stopSelf() }, LinearLayout.LayoutParams(dp(48), match))

        val lp = baseLp(
            dp(300), dp(400),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        head.setOnTouchListener(dragger(p, lp, null))

        // 用带主题的 Context 创建 WebView，避免 Service Context 缺主题导致的兼容问题
        val wv = WebView(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault))
        bridge = WebSetup.attach(wv)

        p.addView(head, LinearLayout.LayoutParams(match, dp(36)))
        p.addView(wv, LinearLayout.LayoutParams(match, 0, 1f))
        val resize = TextView(this).apply { text="◢"; textSize=18f; gravity=Gravity.CENTER; setTextColor(0xFFE0B45A.toInt()) }
        p.addView(resize, LinearLayout.LayoutParams(dp(34), dp(30)).apply { gravity=Gravity.RIGHT })
        resize.setOnTouchListener(resizer(p,lp))

        web = wv
        panel = p
        panelLp = lp
    }

    private fun headBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    /** 每次显示面板前按当前屏幕方向重算尺寸（游戏常横屏） */
    private fun sizePanel() {
        val dm = resources.displayMetrics
        val lp = panelLp ?: return
        val land = dm.widthPixels > dm.heightPixels
        lp.width = if (land) minOf((dm.widthPixels * 0.5f).toInt(), dp(480))
        else (dm.widthPixels * 0.94f).toInt()
        lp.height = if (land) (dm.heightPixels * 0.9f).toInt()
        else (dm.heightPixels * 0.72f).toInt()
        if (!panelPlaced) {
            lp.x = (dm.widthPixels - lp.width) / 2
            lp.y = dp(24)
            panelPlaced = true
        }
        lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - lp.height))
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun resizer(target: View, lp: WindowManager.LayoutParams): View.OnTouchListener {
        var sx=0f;var sy=0f;var sw=0;var sh=0
        return View.OnTouchListener { _,e -> when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;sw=lp.width;sh=lp.height;true}
            MotionEvent.ACTION_MOVE->{val dm=resources.displayMetrics;lp.width=(sw+(e.rawX-sx).toInt()).coerceIn(dp(240),max(dp(240),dm.widthPixels-lp.x));lp.height=(sh+(e.rawY-sy).toInt()).coerceIn(dp(220),max(dp(220),dm.heightPixels-lp.y));try{wm.updateViewLayout(target,lp)}catch(_:Exception){};true}
            MotionEvent.ACTION_UP->true
            else->false
        }}
    }
    // ---------- 显示 / 隐藏 ----------

    private fun showPanel() {
        try {
            removeBubble()
            val p = panel ?: return
            val lp = panelLp ?: return
            sizePanel()
            if (!panelOn) {
                wm.addView(p, lp)
                panelOn = true
            }
            val w = web ?: return
            if (!loaded) {
                loaded = true
                w.loadUrl(WebSetup.URL)
            } else if (bridge?.stale() == true) {
                w.reload()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败："+(e.message ?: "权限或系统限制"), Toast.LENGTH_LONG).show()
            stopSelf() // 例如悬浮窗权限被收回
        }
    }

    private fun showBubble() {
        try {
            removePanel()
            val b = bubble ?: return
            val lp = bubbleLp ?: return
            if (!bubbleOn) {
                wm.addView(b, lp)
                bubbleOn = true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗显示失败："+(e.message ?: "系统限制"), Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun removeBubble() {
        val b = bubble
        if (bubbleOn && b != null) {
            try { wm.removeViewImmediate(b) } catch (_: Exception) {}
            bubbleOn = false
        }
    }

    private fun removePanel() {
        val p = panel
        if (panelOn && p != null) {
            try { wm.removeViewImmediate(p) } catch (_: Exception) {}
            panelOn = false
        }
    }

    // ---------- 拖动 ----------

    private fun dragger(
        target: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)?
    ): View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = max(0, ox + dx.toInt())
                        lp.y = max(0, oy + dy.toInt())
                        try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }
}
