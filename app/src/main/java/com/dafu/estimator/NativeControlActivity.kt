package com.dafu.estimator

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.Locale

class NativeControlActivity : Activity() {
    private var dark = false
    private var bg = 0xFFF4F5FA.toInt()
    private var surface = Color.WHITE
    private var ink = 0xFF1A1E35.toInt()
    private var muted = 0xFF7A7F9E.toInt()
    private var line = 0xFFE6E8F2.toInt()
    private var input = 0xFFF0F2FA.toInt()
    private val gold = 0xFFD99A14.toInt()
    private val green = 0xFF22A06B.toInt()
    private val red = 0xFFE5484D.toInt()

    private lateinit var captureState: TextView
    private lateinit var regionState: TextView
    private lateinit var totalValue: TextView
    private lateinit var purpleValue: TextView
    private lateinit var averageValue: TextView
    private lateinit var estimate: TextView
    private lateinit var suggested: TextView
    private lateinit var diagnostics: TextView
    private lateinit var logs: TextView
    private lateinit var maxBid: EditText
    private var diagnosticsOpen = false
    private var logsOpen = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshData(); refreshLogs(); refreshStatus(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Store.get("dafu-theme") == "dark"
        if (dark) {
            bg = 0xFF0B0E1D.toInt(); surface = 0xFF151A30.toInt()
            ink = 0xFFECEEFA.toInt(); muted = 0xFF9A9FC4.toInt()
            line = 0xFF242B4A.toInt(); input = 0xFF0D1021.toInt()
        }
        actionBar?.hide()
        buildUi()
        handler.post(refresh)
    }

    private fun buildUi() {
        window.statusBarColor = 0xFF1B2250.toInt()
        window.navigationBarColor = if (dark) surface else Color.WHITE
        if (!dark && Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2250.toInt(), 0xFF3B2C72.toInt())).apply {
                val r = dp(22).toFloat(); cornerRadii = floatArrayOf(0f,0f,0f,0f, r,r,r,r)
            }
            addView(label("识别与出价", 26f, Color.WHITE, true))
            addView(label("屏幕识别 · 区域框选 · 估价联动", 13f, 0xCCFFFFFF.toInt()),
                LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(4) })
            addView(label("● 测试模式  ·  不会自动提交出价", 12f, Color.WHITE, true).apply {
                setPadding(dp(12),dp(7),dp(12),dp(7))
                background = rounded(0x33FFFFFF, dp(18), false)
            }, LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(12) })
        }
        root.addView(header, LinearLayout.LayoutParams(-1,-2))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(22)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))

        // 1. Screen capture and manual region selection
        body.addView(section("屏幕识别", "先启动屏幕捕获，再框选拍卖情报面板"))
        val captureCard = card()
        captureState = label("屏幕捕获：检查中…", 13f, muted, true)
        regionState = label("识别范围：全屏", 12.5f, muted)
        captureCard.addView(captureState)
        captureCard.addView(regionState, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        val start = button("开启屏幕识别", 1) {
            ScreenCaptureActivity.launch(this)
            Toast.makeText(this, "请允许屏幕共享；识别在本机离线进行", Toast.LENGTH_LONG).show()
        }
        val stop = button("停止识别", 0) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            NativeLog.add("用户停止屏幕 OCR")
        }
        captureCard.addView(buttonRow(start, stop), LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
        val select = button("手动框选识别区域", 1) { launchRegionSelector() }
        val clear = button("恢复全屏识别", 0) {
            Store.remove("dafu-ocr-region")
            NativeLog.add("已清除手动 OCR 区域，恢复全屏识别")
            Toast.makeText(this, "已恢复全屏识别", Toast.LENGTH_SHORT).show()
            refreshRegion()
        }
        captureCard.addView(buttonRow(select, clear), LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(9) })
        val overlayStart = button("开启悬浮窗", 1) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            } else {
                try {
                    val intent = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    NativeLog.add("用户从原生控制页启动悬浮窗")
                } catch (e: Exception) { NativeLog.add("启动悬浮窗失败：" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
        val overlayStop = button("关闭悬浮窗", 0) {
            stopService(Intent(this, OverlayService::class.java))
            NativeLog.add("用户从原生控制页关闭悬浮窗")
        }
        captureCard.addView(buttonRow(overlayStart, overlayStop), LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(9) })
        body.addView(captureCard, margins(0,dp(9),0,dp(20)))

        // 2. Compact three-card results
        body.addView(section("识别结果", "只显示目标卡片读数；未识别不会伪造为 0"))
        val resultCard = card()
        totalValue = resultRow(resultCard, "千眼其一", "等待识别")
        addDivider(resultCard)
        purpleValue = resultRow(resultCard, "紫品计数仪器", "等待识别")
        addDivider(resultCard)
        averageValue = resultRow(resultCard, "金品均价仪器", "等待识别")
        val diagnosticToggle = button("查看识别详情  ▾", 0) {
            diagnosticsOpen = !diagnosticsOpen
            diagnostics.visibility = if (diagnosticsOpen) View.VISIBLE else View.GONE
            (it as Button).text = if (diagnosticsOpen) "收起识别详情  ▴" else "查看识别详情  ▾"
        }
        resultCard.addView(diagnosticToggle, LinearLayout.LayoutParams(-1,dp(42)).apply { topMargin = dp(10) })
        diagnostics = label("等待 OCR 数据…", 11.5f, muted)
        diagnostics.typeface = Typeface.MONOSPACE
        diagnostics.setLineSpacing(dp(3).toFloat(),1f)
        diagnostics.visibility = View.GONE
        resultCard.addView(diagnostics, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
        body.addView(resultCard, margins(0,dp(9),0,dp(20)))

        // 3. Estimation values
        body.addView(section("当前估价", "由网页估价器实时同步"))
        val values = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (v1,c1) = valueCard("当前估价")
        val (v2,c2) = valueCard("建议出价")
        estimate = c1; suggested = c2
        values.addView(v1, LinearLayout.LayoutParams(0,-2,1f))
        values.addView(v2, LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin = dp(10) })
        body.addView(values, margins(0,dp(9),0,dp(20)))

        // 4. Bid protection
        body.addView(section("出价保护", "设置最高允许出价"))
        val bid = card()
        bid.addView(label("最高出价上限（万）",13f,muted,true))
        maxBid = EditText(this).apply {
            hint = "不填写则不设上限"; setSingleLine(true); textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(ink); setHintTextColor(muted); setPadding(dp(14),0,dp(14),0)
            background = rounded(input,dp(12)); setText(getPreferences(0).getString("max_bid",""))
        }
        bid.addView(maxBid,LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(9); bottomMargin=dp(10) })
        bid.addView(button("保存出价上限",1) {
            getPreferences(0).edit().putString("max_bid",maxBid.text.toString()).apply()
            NativeLog.add("已保存最高出价上限"); Toast.makeText(this,"设置已保存",Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(-1,dp(46)))
        body.addView(bid,margins(0,dp(9),0,dp(20)))

        // 5. Logs folded by default
        body.addView(section("运行日志", "识别、权限与服务事件"))
        val logCard = card()
        val logToggle = button("展开运行日志  ▾",0) {
            logsOpen = !logsOpen; logs.visibility = if(logsOpen) View.VISIBLE else View.GONE
            (it as Button).text = if(logsOpen) "收起运行日志  ▴" else "展开运行日志  ▾"
        }
        logCard.addView(logToggle,LinearLayout.LayoutParams(-1,dp(42)))
        logs = label("暂无运行日志",11f,0xFFB7BCDD.toInt())
        logs.typeface = Typeface.MONOSPACE; logs.setLineSpacing(dp(3).toFloat(),1f)
        logs.setPadding(dp(10),dp(10),dp(10),dp(10))
        logs.background = rounded(if(dark)0xFF070913.toInt() else 0xFF14172B.toInt(),dp(12),false)
        logs.visibility = View.GONE
        logCard.addView(logs,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
        body.addView(logCard,margins(0,dp(9),0,dp(10)))

        val footer = LinearLayout(this).apply { setPadding(dp(16),dp(9),dp(16),dp(11)); setBackgroundColor(surface); elevation=dp(8).toFloat() }
        footer.addView(button("返回估价器",1){finish()},LinearLayout.LayoutParams(-1,dp(50)))
        root.addView(footer,LinearLayout.LayoutParams(-1,-2))
        setContentView(root)
        refreshRegion()
    }

    private fun launchRegionSelector() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,"手动框选需要悬浮窗权限，请先授予权限",Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_:Exception) {}
            return
        }
        try {
            startService(Intent(this,RegionSelectionService::class.java))
            NativeLog.add("打开手动 OCR 区域框选")
            Toast.makeText(this,"拖动屏幕选择拍卖情报卡区域",Toast.LENGTH_SHORT).show()
        } catch(e:Exception) { NativeLog.add("无法打开区域框选："+(e.message?:e.javaClass.simpleName)) }
    }

    private fun refreshRegion() {
        val raw = Store.get("dafu-ocr-region")
        if (raw.isNullOrBlank()) { regionState.text = "识别范围：全屏（尚未设置区域）"; return }
        try {
            val o=JSONObject(raw)
            val l=(o.optDouble("left")*100).toInt(); val t=(o.optDouble("top")*100).toInt()
            val r=(o.optDouble("right")*100).toInt(); val b=(o.optDouble("bottom")*100).toInt()
            regionState.text="识别范围：手动区域  ·  X $l%–$r%  /  Y $t%–$b%"
        } catch(_:Exception) { regionState.text="识别范围：全屏（区域设置无效）" }
    }

    private fun refreshData() {
        if (!::captureState.isInitialized) return
        captureState.text = if (ScreenCaptureService.running) "● 屏幕捕获：运行中" else "○ 屏幕捕获：未启动"
        captureState.setTextColor(if(ScreenCaptureService.running) green else muted)
        refreshRegion()
        try {
            val o=JSONObject(ScreenCaptureService.latestJson)
            val cards=o.optJSONArray("intelCards")
            val byType=(0 until (cards?.length()?:0)).mapNotNull { i -> cards?.optJSONObject(i)?.let { it.optString("type") to it } }.toMap()
            fun show(type:String,unit:String):String {
                val c=byType[type] ?: return "未找到卡片"
                if(!c.optBoolean("valueDetected")) return "卡片已找到，数字未识别"
                val v=c.optString("value")
                return if(unit.isEmpty()) v else "$v $unit"
            }
            totalValue.text=show("auction_info","件")
            purpleValue.text=show("purple_count","件")
            averageValue.text=show("gold_average","")
            val missing=o.optJSONArray("missingTargets")
            val missingText=if(missing==null||missing.length()==0)"无" else (0 until missing.length()).joinToString("、"){missing.optString(it)}
            val missingValues=o.optJSONArray("missingValues")
            val missingValueText=if(missingValues==null||missingValues.length()==0)"无" else (0 until missingValues.length()).joinToString("、"){missingValues.optString(it)}
            val lineArray=o.optJSONArray("lines")
            val lineDetails=(0 until (lineArray?.length()?:0)).joinToString("\n") { i ->
                val item=lineArray?.optJSONObject(i) ?: JSONObject()
                val text=item.optString("text")
                val left=item.optInt("left"); val top=item.optInt("top")
                val right=item.optInt("right"); val bottom=item.optInt("bottom")
                val height=item.optInt("height",bottom-top)
                "$i. $text  [x:$left-$right y:$top-$bottom h:$height px conf:${item.optDouble("confidence",0.0)}]"
            }
            val timing=o.optJSONObject("timing") ?: JSONObject()
            diagnostics.text="状态：${o.optString("status","等待识别")}\nOCR 引擎：${o.optString("engine","初始化中")}\n耗时：${timing.optDouble("totalSeconds",0.0)}s（检测 ${timing.optDouble("detectionSeconds",0.0)}s / 识别 ${timing.optDouble("recognitionSeconds",0.0)}s）\n截图尺寸：${o.optInt("screenWidth")}×${o.optInt("screenHeight")}\nOCR 文字行：${o.optInt("lineCount")}\n检测卡片：${o.optInt("detectedCount")}/3\n数字有效：${o.optInt("valueDetectedCount")}/3\n未找到：$missingText\n未读到数字：$missingValueText\n识别范围：${if(o.optBoolean("regionUsed"))"手动区域" else "全屏"}\n\n逐行 OCR（含坐标/像素高度）：\n${lineDetails.ifBlank { "没有识别到文字行" }.take(12000)}"
        } catch(_:Exception) { }
        val raw=Store.get("dafu-auction-live") ?: return
        try {
            val o=JSONObject(raw)
            setAmount(estimate,o.optDouble("valuationWan",Double.NaN))
            setAmount(suggested,o.optDouble("suggestedBidWan",Double.NaN))
        } catch(_:Exception) {}
    }

    private fun refreshStatus() {
        if (!::captureState.isInitialized) return
        captureState.text=if(ScreenCaptureService.running)"● 屏幕捕获：运行中" else "○ 屏幕捕获：未启动"
        captureState.setTextColor(if(ScreenCaptureService.running)green else muted)
    }
    private fun refreshLogs() { if(::logs.isInitialized && logsOpen) logs.text=NativeLog.snapshot(30).joinToString("\n").ifBlank{"暂无运行日志"} }

    private fun section(title:String,sub:String):View {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val stripe=View(this).apply { background=rounded(gold,dp(3),false) }
        row.addView(stripe,LinearLayout.LayoutParams(dp(4),dp(32)).apply{rightMargin=dp(10)})
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL
            addView(label(title,17f,ink,true)); addView(label(sub,12f,muted)) }
        row.addView(col); return row
    }
    private fun card()=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(15),dp(14),dp(15),dp(14))
        background=rounded(surface,dp(16)); elevation=if(dark)0f else dp(2).toFloat()
    }
    private fun resultRow(parent:LinearLayout,title:String,initial:String):TextView {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        row.addView(label(title,13.5f,muted,true),LinearLayout.LayoutParams(0,-2,1f))
        val value=label(initial,14f,ink,true); value.gravity=Gravity.END
        row.addView(value,LinearLayout.LayoutParams(-2,-2))
        parent.addView(row); return value
    }
    private fun addDivider(parent:LinearLayout) {
        parent.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(11);bottomMargin=dp(11)})
    }
    private fun valueCard(title:String):Pair<View,TextView> {
        val c=card(); c.addView(label(title,12.5f,muted,true))
        val value=label("等待数据",23f,ink,true); c.addView(value,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
        return c to value
    }
    private fun buttonRow(a:View,b:View)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        addView(a,LinearLayout.LayoutParams(0,dp(46),1f))
        addView(b,LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(9)})
    }
    private fun button(text:String,style:Int,action:(View)->Unit):Button {
        val shape=when(style){
            1->GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFFF2B93B.toInt(),gold))
            2->GradientDrawable().apply{setColor(red)}
            else->GradientDrawable().apply{setColor(surface);setStroke(dp(1),line)}
        }.apply{cornerRadius=dp(12).toFloat()}
        val fg=if(style==0)ink else Color.WHITE
        val ripple=if(style==0)0x22000000 else 0x33FFFFFF
        return Button(this).apply{
            this.text=text;isAllCaps=false;textSize=13.5f;typeface=Typeface.DEFAULT_BOLD;setTextColor(fg)
            stateListAnimator=null;elevation=0f;background=RippleDrawable(ColorStateList.valueOf(ripple),shape,null)
            setOnClickListener{action(it)}
        }
    }
    private fun label(text:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD
    }
    private fun rounded(color:Int,radius:Int,stroke:Boolean=true)=GradientDrawable().apply{
        setColor(color);cornerRadius=radius.toFloat();if(stroke)setStroke(dp(1),line)
    }
    private fun margins(l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).apply{
        setMargins(l,t,r,b)
    }
    private fun setAmount(view:TextView,value:Double){
        if(value.isNaN()||value.isInfinite()){view.text="等待数据";view.setTextColor(muted);return}
        view.text=String.format(Locale.US,"%.2f 万",value);view.setTextColor(gold)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onResume(){super.onResume();if(::captureState.isInitialized){refreshData();refreshStatus()}}
    override fun onDestroy(){handler.removeCallbacks(refresh);super.onDestroy()}
}
