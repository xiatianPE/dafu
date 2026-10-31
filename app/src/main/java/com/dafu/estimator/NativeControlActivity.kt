package com.dafu.estimator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.*

class NativeControlActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var estimate: TextView
    private lateinit var suggested: TextView
    private lateinit var maxBid: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(18, 21, 26))
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("识别与出价", 25f, Color.WHITE, true))
        body.addView(text("原生安卓控制台", 13f, 0xFF9AA4B2.toInt()))
        status = card("屏幕识别状态：检查中")
        body.addView(status, lp(0, 16, 0, 8))
        body.addView(button("开启屏幕识别权限") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        body.addView(button("读取当前屏幕") {
            val json = ScreenAssistService.snapshotJson()
            val count = try { org.json.JSONObject(json).optJSONArray("items")?.length() ?: 0 } catch (_: Exception) { 0 }
            Toast.makeText(this, "读取到 $count 个可访问节点", Toast.LENGTH_SHORT).show()
            updateStatus()
        })

        body.addView(text("估价数据", 18f, Color.WHITE, true), lp(0, 18, 0, 8))
        estimate = card("当前估价：等待网页估价")
        suggested = card("建议出价：等待网页估价")
        body.addView(estimate, lp(0, 0, 0, 8))
        body.addView(suggested, lp(0, 0, 0, 8))

        body.addView(text("自动出价设置", 18f, Color.WHITE, true), lp(0, 18, 0, 8))
        body.addView(text("最高出价上限（万）", 13f, 0xFFB6BEC9.toInt()))
        maxBid = EditText(this).apply {
            hint = "不填写则不设上限"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF77808C.toInt())
            setBackground(bg(0xFF252B33.toInt()))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(getPreferences(0).getString("max_bid", ""))
        }
        body.addView(maxBid, lp(0, 6, 0, 10))
        body.addView(button("保存最高出价") {
            getPreferences(0).edit().putString("max_bid", maxBid.text.toString()).apply()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })
        body.addView(button("启动识别测试") {
            Toast.makeText(this, "测试模式：仅读取无障碍节点，不会提交出价", Toast.LENGTH_LONG).show()
        })
        body.addView(button("停止流程") {
            Toast.makeText(this, "流程已停止", Toast.LENGTH_SHORT).show()
        })
        body.addView(button("开启原生悬浮窗") {
            val result = Bridge(this).setOverlayEnabled(true)
            if (result == "permission_required") Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
        })
        body.addView(button("关闭悬浮窗") { stopService(Intent(this, OverlayService::class.java)) })
        body.addView(text("说明：估价数据由网页计算器发布到原生层。当前屏幕识别读取无障碍节点；游戏画面 OCR、控件定位及真实提交仍待界面素材校准。", 12f, 0xFF8993A0.toInt()), lp(0, 16, 0, 12))
        root.addView(button("返回估价器") { finish() })
        setContentView(root)
        refreshData()
        updateStatus()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus(); if (::estimate.isInitialized) refreshData() }

    private fun refreshData() {
        val raw = Store.get("dafu-auction-live") ?: return
        try {
            val o = org.json.JSONObject(raw)
            estimate.text = "当前估价\\n" + amount(o.optDouble("valuationWan", Double.NaN))
            suggested.text = "建议出价\\n" + amount(o.optDouble("suggestedBidWan", Double.NaN))
        } catch (_: Exception) {}
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        status.text = "屏幕识别服务：" + if (ScreenAssistService.isConnected()) "已连接" else "未连接"
    }

    private fun amount(v: Double) = if (v.isNaN() || v.isInfinite()) "等待估价" else String.format("%.2f 万", v)
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun card(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(14), dp(13), dp(14), dp(13)); background = bg(0xFF252B33.toInt())
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); background = bg(0xFF92733D.toInt()); setOnClickListener { action() }
    }
    private fun bg(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun lp(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}