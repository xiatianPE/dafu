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
import android.widget.*
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
    private var panel: LinearLayout? = null
    private var valueText: TextView? = null
    private var logText: TextView? = null
    private var logArea: LinearLayout? = null
    private var expanded = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshContent(); handler.postDelayed(this, 900) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); wm=getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onStartCommand(intent: Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        startForegroundCompat()
        if(panel==null){running=true;createNativePanel();handler.post(refresh);NativeLog.add("原生悬浮窗已启动")}
        return START_STICKY
    }
    private fun startForegroundCompat() {
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"达芙原生估价悬浮窗",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,0,Intent(this,OverlayService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,1,Intent(this,NativeControlActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("达芙原生悬浮窗运行中")
            .setContentText("显示估价、建议出价与运行日志").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_stat),"关闭",stop).build()).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID,n)
    }
    private fun createNativePanel() {
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(10),dp(13),dp(10))
            background=GradientDrawable().apply{setColor(0xF2FFFFFF.toInt());cornerRadius=dp(16).toFloat();setStroke(dp(1),0xFFDCDFEE.toInt())}
            elevation=dp(9).toFloat()
        }
        root.addView(TextView(this).apply{text="达芙估价器";textSize=12f;setTextColor(0xFF666C90.toInt());typeface=Typeface.DEFAULT_BOLD})
        valueText=TextView(this).apply{text="当前估价：等待数据\n建议出价：等待数据";textSize=15f;setTextColor(0xFF1A1E35.toInt());typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(5),0,dp(7))}
        root.addView(valueText)
        val logToggle=TextView(this).apply{text="运行日志  ▾";textSize=12f;setTextColor(0xFF8A6A2E.toInt());gravity=Gravity.END;setPadding(0,dp(4),0,dp(2));setOnClickListener{toggleLogs()}}
        root.addView(logToggle)
        logArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(8),dp(7),dp(8),dp(7));background=GradientDrawable().apply{setColor(0xFFF2F3F9.toInt());cornerRadius=dp(9).toFloat()}}
        logText=TextView(this).apply{text="暂无日志";textSize=10.5f;setTextColor(0xFF666C90.toInt());typeface=Typeface.MONOSPACE;setLineSpacing(dp(2).toFloat(),1f)}
        logArea?.addView(logText);root.addView(logArea)
        val lp=WindowManager.LayoutParams(dp(230),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=dp(12);y=dp(180)}
        root.setOnTouchListener(dragListener(root,lp))
        panel=root
        try{wm.addView(root,lp)}catch(_:Exception){stopSelf()}
    }
    private fun toggleLogs(){
        expanded=!expanded;logArea?.visibility=if(expanded)View.VISIBLE else View.GONE
        (panel?.getChildAt(2) as? TextView)?.text=if(expanded)"运行日志  ▴" else "运行日志  ▾"
        refreshLogs()
    }
    private fun refreshContent(){
        val raw=Store.get("dafu-auction-live")
        if(raw!=null)try{val d=JSONObject(raw);valueText?.text="当前估价：\${format(d.optDouble("valuationWan",Double.NaN))}\n建议出价：\${format(d.optDouble("suggestedBidWan",Double.NaN))}"}catch(_:Exception){}
        if(expanded)refreshLogs()
    }
    private fun refreshLogs(){logText?.text=NativeLog.snapshot(8).joinToString("\n").ifBlank{"暂无运行日志"}}
    private fun format(v:Double)=if(v.isNaN()||v.isInfinite())"等待数据" else String.format("%.2f 万",v)
    private fun dragListener(view:View,lp:WindowManager.LayoutParams):View.OnTouchListener{
        var sx=0f;var sy=0f;var ox=0;var oy=0;var moved=false
        val slop=ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;ox=lp.x;oy=lp.y;moved=false;true}
            MotionEvent.ACTION_MOVE->{val dx=e.rawX-sx;val dy=e.rawY-sy;if(kotlin.math.abs(dx)>slop||kotlin.math.abs(dy)>slop)moved=true
                if(moved){val dm=resources.displayMetrics;lp.x=(ox+dx.toInt()).coerceIn(0,max(0,dm.widthPixels-view.width));lp.y=(oy+dy.toInt()).coerceIn(0,max(0,dm.heightPixels-view.height));try{wm.updateViewLayout(view,lp)}catch(_:Exception){}}
                true}
            MotionEvent.ACTION_UP->true
            else->false
        }}
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy(){running=false;handler.removeCallbacks(refresh);NativeLog.add("原生悬浮窗已关闭");panel?.let{try{wm.removeViewImmediate(it)}catch(_:Exception){}};panel=null;super.onDestroy()}
}