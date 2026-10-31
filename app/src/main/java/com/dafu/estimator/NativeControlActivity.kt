package com.dafu.estimator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

class NativeControlActivity : Activity() {
    private var dark = false
    private var bgColor = 0xFFF2F3F9.toInt()
    private var cardColor = Color.WHITE
    private var textColor = 0xFF1A1E35.toInt()
    private var mutedColor = 0xFF666C90.toInt()
    private var lineColor = 0xFFDCD FEE.toInt()
    private var inputColor = 0xFFECEEF8.toInt()
    private val gold = 0xFFD99A14.toInt()
    private lateinit var status: TextView
    private lateinit var estimate: TextView
    private lateinit var suggested: TextView
    private lateinit var logView: TextView
    private lateinit var maxBid: EditText
    private lateinit var overlayStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshData(); refreshLogs(); refreshStatus(); handler.postDelayed(this, 900) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Store.get("dafu-theme") == "dark"
        if (dark) {
            bgColor=0xFF0D1021.toInt(); cardColor=0xFF161A30.toInt()
            textColor=0xFFECE EFA.toInt(); mutedColor=0xFF9A9FC4.toInt()
            lineColor=0xFF2A3050.toInt(); inputColor=0xFF0D1021.toInt()
        }
        buildUi()
        handler.post(refresh)
    }

    private fun buildUi() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(14));setBackgroundColor(cardColor)
            addView(label("识别与出价",25f,textColor,true))
            addView(label("屏幕识别 · 估价联动 · 自动出价控制",13f,mutedColor))
        }
        root.addView(header,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(22)) }
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        body.addView(sectionTitle("运行状态","权限与服务连接情况"))
        status=label("正在检查屏幕识别状态…",14f,textColor)
        body.addView(card(status),space(0,8,0,12))
        val permission=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        permission.addView(button("开启屏幕识别",false){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},LinearLayout.LayoutParams(0,dp(46),1f))
        permission.addView(button("读取屏幕",true){
            val raw=ScreenAssistService.snapshotJson()
            val count=try{JSONObject(raw).optJSONArray("items")?.length()?:0}catch(_:Exception){0}
            NativeLog.add("手动读取屏幕：获取到 $count 个节点")
            Toast.makeText(this,"读取到 $count 个可访问节点",Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(8)})
        body.addView(permission,space(0,0,0,18))

        body.addView(sectionTitle("当前估价","由网页估价器实时同步"))
        val values=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        estimate=valueCard("当前估价","等待数据");suggested=valueCard("建议出价","等待数据")
        values.addView(estimate,LinearLayout.LayoutParams(0,-2,1f));values.addView(Space(this),LinearLayout.LayoutParams(dp(8),1))
        values.addView(suggested,LinearLayout.LayoutParams(0,-2,1f))
        body.addView(values,space(0,8,0,18))

        body.addView(sectionTitle("出价参数","设置执行时的价格保护"))
        val bidCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(14));background=rounded(cardColor,dp(14)) }
        bidCard.addView(label("最高出价上限（万）",13f,mutedColor,true))
        maxBid=EditText(this).apply {
            hint="不填写则不设上限";setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textColor);setHintTextColor(mutedColor);setPadding(dp(12),0,dp(12),0)
            background=rounded(inputColor,dp(10));setText(getPreferences(0).getString("max_bid",""))
        }
        bidCard.addView(maxBid,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(7);bottomMargin=dp(10)})
        bidCard.addView(button("保存出价上限",true){
            getPreferences(0).edit().putString("max_bid",maxBid.text.toString()).apply()
            NativeLog.add("已保存最高出价上限");Toast.makeText(this,"设置已保存",Toast.LENGTH_SHORT).show()
        })
        body.addView(bidCard,space(0,8,0,18))

        body.addView(sectionTitle("运行控制","当前为识别测试模式，不会自动提交出价"))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        controls.addView(button("启动识别测试",true){NativeLog.add("识别测试已启动");ScreenAssistService.snapshotJson();Toast.makeText(this,"测试模式：仅读取屏幕节点",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(46),1f))
        controls.addView(button("紧急停止",false){NativeLog.add("收到停止指令");stopService(Intent(this,OverlayService::class.java));Toast.makeText(this,"流程已停止",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(8)})
        body.addView(controls,space(0,8,0,8))
        overlayStatus=label("悬浮窗：未启动",13f,mutedColor);body.addView(overlayStatus,space(0,2,0,8))
        val overlays=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        overlays.addView(button("开启原生悬浮窗",true){
            val result=Bridge(this@NativeControlActivity).setOverlayEnabled(true);NativeLog.add("请求开启原生悬浮窗")
            if(result=="permission_required")Toast.makeText(this@NativeControlActivity,"请授予悬浮窗权限后重试",Toast.LENGTH_LONG).show()
        },LinearLayout.LayoutParams(0,dp(46),1f))
        overlays.addView(button("关闭悬浮窗",false){stopService(Intent(this@NativeControlActivity,OverlayService::class.java));NativeLog.add("已关闭原生悬浮窗")},LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(8)})
        body.addView(overlays,space(0,0,0,18))

        body.addView(sectionTitle("运行日志","最近的识别、权限和悬浮窗事件"))
        val logs=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(cardColor,dp(14))}
        logView=label("暂无运行日志",12.5f,mutedColor);logView.typeface=Typeface.MONOSPACE;logView.setLineSpacing(dp(3).toFloat(),1f)
        logs.addView(logView);body.addView(logs,space(0,8,0,14))
        root.addView(button("返回估价器",true){finish()},LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(16),dp(6),dp(16),dp(12))})
        setContentView(root)
    }

    private fun refreshData(){
        val raw=Store.get("dafu-auction-live")?:return
        try{val o=JSONObject(raw);estimate.text="当前估价\n"+amount(o.optDouble("valuationWan",Double.NaN));suggested.text="建议出价\n"+amount(o.optDouble("suggestedBidWan",Double.NaN))}catch(_:Exception){}
    }
    private fun refreshStatus(){
        if(!::status.isInitialized)return
        val enabled=ScreenAssistService.isEnabled(this);val connected=ScreenAssistService.isConnected()
        status.text="无障碍权限："+(if(enabled)"已开启" else "未开启")+"     服务连接："+(if(connected)"正常" else "未连接")
        if(::overlayStatus.isInitialized)overlayStatus.text="悬浮窗："+(if(OverlayService.running)"运行中" else "未启动")
    }
    private fun refreshLogs(){if(::logView.isInitialized)logView.text=NativeLog.snapshot(24).joinToString("\n").ifBlank{"暂无运行日志"}}
    private fun amount(v:Double)=if(v.isNaN()||v.isInfinite())"等待数据" else String.format("%.2f 万",v)
    private fun sectionTitle(title:String,sub:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(label(title,17f,textColor,true));addView(label(sub,12f,mutedColor))}
    private fun valueCard(title:String,value:String)=TextView(this).apply{text="$title\n$value";textSize=17f;setTextColor(textColor);typeface=Typeface.DEFAULT_BOLD;setPadding(dp(12),dp(13),dp(8),dp(13));background=rounded(cardColor,dp(14))}
    private fun card(child:View)=FrameLayout(this).apply{setPadding(dp(13),dp(12),dp(13),dp(12));background=rounded(cardColor,dp(13));addView(child)}
    private fun label(s:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD}
    private fun button(s:String,primary:Boolean,action:()->Unit)=Button(this).apply{text=s;isAllCaps=false;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(if(primary)Color.WHITE else textColor);background=rounded(if(primary)gold else cardColor,dp(11));setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat();setStroke(dp(1),lineColor)}
    private fun space(l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onResume(){super.onResume();if(::status.isInitialized)refreshStatus();if(::estimate.isInitialized)refreshData()}
    override fun onDestroy(){handler.removeCallbacks(refresh);super.onDestroy()}
}