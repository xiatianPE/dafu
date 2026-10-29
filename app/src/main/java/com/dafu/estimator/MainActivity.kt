package com.dafu.estimator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B1B1F.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val title = TextView(this).apply {
            text = "达芙拍卖估价器"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        toggle = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onToggle() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(toggle, LinearLayout.LayoutParams(wrap, dp(36)))

        web = WebView(this)
        bridge = WebSetup.attach(web)

        root.addView(bar, LinearLayout.LayoutParams(match, dp(44)))
        root.addView(web, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        web.loadUrl(WebSetup.URL)
    }

    override fun onResume() {
        super.onResume()
        refreshToggle(OverlayService.running)
        // 悬浮窗里改过数据，回到主界面时刷新
        if (bridge.stale()) web.reload()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun refreshToggle(on: Boolean) {
        toggle.text = if (on) "关闭悬浮窗" else "开启悬浮窗"
    }

    private fun onToggle() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            refreshToggle(false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许「显示在其他应用上层」，授权后回来再点一次", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        refreshToggle(true)
    }
}
