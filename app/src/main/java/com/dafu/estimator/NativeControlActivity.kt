package com.dafu.estimator

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.Locale

class NativeControlActivity : Activity() {
    private var dark = false
    private var bgColor = 0xFFF4F5FA.toInt()
    private var cardColor = Color.WHITE
    private var textColor = 0xFF1A1E35.toInt()
    private var mutedColor = 0xFF7A7F9E.toInt()
    private var lineColor = 0xFFE6E8F2.toInt()
    private var inputColor = 0xFFF0F2FA.toInt()
    private val gold = 0xFFD99A14.toInt()
    private val goldLight = 0xFFF2B93B.toInt()
    private val okColor = 0xFF22B573.toInt()
    private val badColor = 0xFFE5484D.toInt()

    private lateinit var accDot: View
    private lateinit var accText: TextView
    private lateinit var svcDot: View
    private lateinit var svcText: TextView
    private lateinit var ovDot: View
    private lateinit var overlayStatus: TextView
    private lateinit var estimate: TextView
    private lateinit var suggested: TextView
    private lateinit var logView: TextView
    private lateinit var recognitionView: TextView
    private lateinit var maxBid: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshData(); refreshLogs(); refreshStatus(); handler.postDelayed(this, 900) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Store.get("dafu-theme") == "dark"
        if (dark) {
            bgColor = 0xFF0B0E1D.toInt(); cardColor = 0xFF151A30.toInt()
            textColor = 0xFFECEEFA.toInt(); mutedColor = 0xFF9A9FC4.toInt()
            lineColor = 0xFF242B4A.toInt(); inputColor = 0xFF0D1021.toInt()
        }
        actionBar?.hide()
        buildUi()
        handler.post(refresh)
    }

    private fun buildUi() {
        window.statusBarColor = 0xFF1B2250.toInt()
        window.navigationBarColor = if (dark) 0xFF151A30.toInt() else Color.WHITE
        if (!dark && Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }

        // ---------- 顶部标题区 ----------
        val pill = label("●  测试模式 · 不会自动提交出价", 12f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(20).toFloat() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2250.toInt(), 0xFF3B2C72.toInt())
            ).apply {
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            elevation = dp(4).toFloat()
            addView(label("识别与出价", 27f, Color.WHITE, true))
            addView(label("屏幕识别 · 估价联动 · 自动出价控制", 13f, 0xCCFFFFFF.toInt()),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---------- 运行状态 ----------
        body.addView(sectionTitle("运行状态", "权限与服务连接情况"))
        val (r1, d1, t1) = statusRow("无障碍权限")
        val (r2, d2, t2) = statusRow("服务连接")
        val (r3, d3, t3) = statusRow("悬浮窗")
        accDot = d1; accText = t1; svcDot = d2; svcText = t2; ovDot = d3; overlayStatus = t3
        val statusCard = cardBox()
        statusCard.addView(r1); addDivider(statusCard)
        statusCard.addView(r2); addDivider(statusCard)
        statusCard.addView(r3)
        body.addView(statusCard, space(0, 10, 0, 12))

        val openAcc = button("开启屏幕识别", 0) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val readScreen = button("读取无障碍节点", 0) {
            val raw = ScreenAssistService.snapshotJson()
            val count = try { JSONObject(raw).optJSONArray("items")?.length() ?: 0 } catch (_: Exception) { 0 }
            NativeLog.add("手动读取屏幕：获取到 $count 个节点")
            Toast.makeText(this, "读取到 $count 个可访问节点", Toast.LENGTH_SHORT).show()
        }
        body.addView(buttonRow(openAcc, readScreen), space(0, 0, 0, 8))
        val startOcr = button("开启屏幕 OCR", 1) {
            ScreenCaptureActivity.launch(this)
            Toast.makeText(this, "请允许屏幕共享；游戏画面会在本机离线识别", Toast.LENGTH_LONG).show()
        }
        val stopOcr = button("停止 OCR", 0) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            NativeLog.add("用户停止屏幕 OCR")
        }
        body.addView(buttonRow(startOcr, stopOcr), space(0, 0, 0, 14))
        body.addView(sectionTitle("屏幕识别结果", "回合、中央情报、出价按钮与数字键盘"))
        val recognitionCard = cardBox()
        recognitionView = label("等待屏幕 OCR…", 12f, mutedColor)
        recognitionView.setLineSpacing(dp(3).toFloat(), 1f)
        recognitionCard.addView(recognitionView)
        body.addView(recognitionCard, space(0, 8, 0, 24))

        // ---------- 当前估价 ----------
        body.addView(sectionTitle("当前估价", "由网页估价器实时同步"))
        val (c1, v1) = valueCard("当前估价", textColor)
        val (c2, v2) = valueCard("建议出价", gold)
        estimate = v1; suggested = v2
        val values = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.addView(c1, LinearLayout.LayoutParams(0, -2, 1f))
        values.addView(c2, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        body.addView(values, space(0, 10, 0, 24))

        // ---------- 出价参数 ----------
        body.addView(sectionTitle("出价参数", "设置执行时的价格保护"))
        val bidCard = cardBox()
        bidCard.addView(label("最高出价上限（万）", 13f, mutedColor, true))
        maxBid = EditText(this).apply {
            hint = "不填写则不设上限"; setSingleLine(true); textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textColor); setHintTextColor(mutedColor); setPadding(dp(14), 0, dp(14), 0)
            background = rounded(inputColor, dp(12)); setText(getPreferences(0).getString("max_bid", ""))
        }
        bidCard.addView(maxBid, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10); bottomMargin = dp(12) })
        bidCard.addView(button("保存出价上限", 1) {
            getPreferences(0).edit().putString("max_bid", maxBid.text.toString()).apply()
            NativeLog.add("已保存最高出价上限"); Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(bidCard, space(0, 10, 0, 24))

        // ---------- 运行控制 ----------
        body.addView(sectionTitle("运行控制", "当前为识别测试模式，不会自动提交出价"))
        val startTest = button("启动识别测试", 1) {
            NativeLog.add("识别测试已启动"); ScreenAssistService.snapshotJson()
            Toast.makeText(this, "测试模式：仅读取屏幕节点", Toast.LENGTH_SHORT).show()
        }
        val stopAll = button("紧急停止", 2) {
            NativeLog.add("收到停止指令"); stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "流程已停止", Toast.LENGTH_SHORT).show()
        }
        body.addView(buttonRow(startTest, stopAll), space(0, 10, 0, 10))
        val overlayOn = button("开启原生悬浮窗", 1) {
            val result = Bridge(this@NativeControlActivity).setOverlayEnabled(true); NativeLog.add("请求开启原生悬浮窗")
            if (result == "permission_required") Toast.makeText(this@NativeControlActivity, "请授予悬浮窗权限后重试", Toast.LENGTH_LONG).show()
        }
        val overlayOff = button("关闭悬浮窗", 0) {
            stopService(Intent(this@NativeControlActivity, OverlayService::class.java)); NativeLog.add("已关闭原生悬浮窗")
        }
        body.addView(buttonRow(overlayOn, overlayOff), space(0, 0, 0, 24))

        // ---------- 运行日志 ----------
        body.addView(sectionTitle("运行日志", "最近的识别、权限和悬浮窗事件"))
        val logs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (dark) 0xFF070913.toInt() else 0xFF14172B.toInt(), dp(16), false)
        }
        logView = label("暂无运行日志", 12f, 0xFFB7BCDD.toInt())
        logView.typeface = Typeface.MONOSPACE; logView.setLineSpacing(dp(4).toFloat(), 1f)
        logs.addView(logView)
        body.addView(logs, space(0, 10, 0, 4))

        // ---------- 底部栏 ----------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(cardColor)
            elevation = dp(8).toFloat()
        }
        bar.addView(button("返回估价器", 1) { finish() }, LinearLayout.LayoutParams(-1, dp(50)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    // ================= 数据刷新 =================
    private fun refreshData() {
        if (::recognitionView.isInitialized) {
            try {
                val o = JSONObject(ScreenCaptureService.latestJson)
                val cards = o.optJSONArray("intelCards")
                val cardText = (0 until (cards?.length() ?: 0)).mapNotNull { i ->
                    cards?.optJSONObject(i)?.let { "• " + it.optString("title") + if (it.optString("description").isNotBlank()) "： " + it.optString("description") else "" }
                }.joinToString("\n")
                val round = if (o.isNull("round")) "未识别" else "第" + o.optString("round") + "回合"
                recognitionView.text = "状态：" + o.optString("status", if (ScreenCaptureService.running) "识别中" else "未启动") +
                    "\n回合：" + round +
                    "\n出价按钮：" + (if (o.optBoolean("bidButtonVisible")) "可见" else "未确认") +
                    "\n放弃按钮：" + (if (o.optBoolean("giveUpButtonVisible")) "可见" else "未确认") +
                    "\n数字键盘：" + (if (o.optBoolean("bidKeyboardVisible")) "可见" else "未确认") +
                    "\n置信度：" + o.optString("confidence", "—") +
                    "\n情报：" + (if (cardText.isBlank()) "尚未识别到情报卡" else "\n" + cardText) +
                    "\n原始文字：" + o.optString("rawText", "").take(500)
            } catch (_: Exception) { recognitionView.text = "等待屏幕 OCR 授权或识别结果…" }
        }
        val raw = Store.get("dafu-auction-live") ?: return
        try {
            val o = JSONObject(raw)
            setAmount(estimate, o.optDouble("valuationWan", Double.NaN))
            setAmount(suggested, o.optDouble("suggestedBidWan", Double.NaN))
        } catch (_: Exception) {}
    }

    private fun refreshStatus() {
        if (!::accText.isInitialized) return
        val enabled = ScreenAssistService.isEnabled(this)
        val connected = ScreenAssistService.isConnected()
        setState(accDot, accText, enabled, "已开启", "未开启", badColor)
        setState(svcDot, svcText, connected, "正常", "未连接", badColor)
        setState(ovDot, overlayStatus, OverlayService.running, "运行中", "未启动", mutedColor)
    }

    private fun refreshLogs() {
        if (::logView.isInitialized) logView.text = NativeLog.snapshot(24).joinToString("\n").ifBlank { "暂无运行日志" }
    }

    private fun setAmount(tv: TextView, v: Double) {
        if (v.isNaN() || v.isInfinite()) {
            tv.text = "等待数据"; tv.textSize = 17f; tv.setTextColor(mutedColor); return
        }
        val num = String.format(Locale.US, "%.2f", v)
        val s = SpannableString("$num 万")
        s.setSpan(RelativeSizeSpan(0.6f), num.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = s; tv.textSize = 26f; tv.setTextColor((tv.tag as? Int) ?: textColor)
    }

    private fun setState(dot: View, tv: TextView, ok: Boolean, okText: String, badText: String, badTint: Int) {
        val c = if (ok) okColor else badTint
        tv.text = if (ok) okText else badText
        tv.setTextColor(c)
        setDot(dot, c)
    }

    // ================= UI 组件 =================
    private fun sectionTitle(title: String, sub: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bar = View(this).apply {
            background = GradientDrawable().apply { setColor(gold); cornerRadius = dp(2).toFloat() }
        }
        row.addView(bar, LinearLayout.LayoutParams(dp(4), dp(32)).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 17f, textColor, true))
            addView(label(sub, 12f, mutedColor))
        }
        row.addView(col)
        return row
    }

    private fun statusRow(name: String): Triple<LinearLayout, View, TextView> {
        val dot = View(this)
        setDot(dot, mutedColor)
        val state = label("检查中…", 13.5f, mutedColor, true)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        row.addView(label(name, 14.5f, textColor), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(state)
        return Triple(row, dot, state)
    }

    private fun setDot(v: View, c: Int) {
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    }

    private fun addDivider(parent: LinearLayout) {
        val d = View(this).apply { setBackgroundColor(lineColor) }
        parent.addView(d, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
    }

    private fun valueCard(title: String, accent: Int): Pair<LinearLayout, TextView> {
        val v = TextView(this).apply {
            tag = accent; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
        }
        setAmount(v, Double.NaN)
        val c = cardBox()
        c.addView(label(title, 12.5f, mutedColor, true))
        c.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        return Pair(c, v)
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(cardColor, dp(16))
        elevation = if (dark) 0f else dp(2).toFloat()
    }

    private fun buttonRow(a: View, b: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a, LinearLayout.LayoutParams(0, dp(48), 1f))
        r.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        return r
    }

    // style: 0 次要（描边） 1 主要（金色渐变） 2 危险（红色）
    private fun button(s: String, style: Int, action: () -> Unit): Button {
        val radius = dp(12).toFloat()
        val content: GradientDrawable = when (style) {
            1 -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(goldLight, gold))
            2 -> GradientDrawable().apply { setColor(badColor) }
            else -> GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), lineColor) }
        }
        content.cornerRadius = radius
        val fg = if (style == 0) textColor else Color.WHITE
        val ripple = if (style == 0) 0x22000000 else 0x33FFFFFF
        return Button(this).apply {
            text = s; isAllCaps = false; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            stateListAnimator = null; elevation = 0f
            background = RippleDrawable(ColorStateList.valueOf(ripple), content, null)
            setOnClickListener { action() }
        }
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int, stroke: Boolean = true) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); if (stroke) setStroke(dp(1), lineColor)
    }

    private fun space(l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::accText.isInitialized) refreshStatus()
        if (::estimate.isInitialized) refreshData()
    }

    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }
}
