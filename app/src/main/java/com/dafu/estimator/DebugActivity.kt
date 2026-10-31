package com.dafu.estimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

class DebugActivity : Activity() {
 private lateinit var status: TextView
 private lateinit var result: TextView
 private lateinit var logs: TextView
 private val handler = Handler(Looper.getMainLooper())
 private val refresh = object : Runnable {
  override fun run() {
   if (isFinishing) return
   status.text = permissionStatus(this@DebugActivity).toString(2)
   val a = org.json.JSONArray(ScreenAssistService.logsJson())
   logs.text = (0 until a.length()).joinToString("\n") { a.optString(it) }
   handler.postDelayed(this, 800)
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  ScreenAssistService.log("打开原生辅助调试页面")
  val root = LinearLayout(this).apply { orientation = 1; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFF17191D.toInt()) }
  root.addView(TextView(this).apply { text = "达芙 · 屏幕辅助与自动出价"; textSize = 20f; setTextColor(0xFFF0C56B.toInt()) })
  status = TextView(this).apply { textSize = 13f; setTextColor(-1); setPadding(0, dp(12), 0, dp(12)) }
  root.addView(status)
  root.addView(button("开启无障碍权限") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
  root.addView(button("请求屏幕捕获权限（截图/OCR备用）") {
   val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
   startActivityForResult(m.createScreenCaptureIntent(), 41)
   ScreenAssistService.log("请求系统屏幕捕获授权")
  })
  root.addView(button("开启悬浮窗权限") {
   if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))
    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
  })
  root.addView(button("手动读取当前屏幕") {
   val d = JSONObject(ScreenAssistService.snapshotJson())
   result.text = "前台应用：" + d.optString("package", "未知") + "\n可访问节点：" + d.optInt("count") + "\n\n" + d.optJSONArray("items").toString(2)
  })
  root.addView(Switch(this).apply {
   text = "自动读取屏幕（每 1.2 秒）"; setTextColor(-1)
   setOnCheckedChangeListener { _, enabled -> ScreenAssistService.setAutoReadEnabled(enabled) }
  })
  root.addView(TextView(this).apply { text = "最近一次读取"; setTextColor(0xFFF0C56B.toInt()) })
  result = TextView(this).apply { text = "尚未读取"; textSize = 12f; setTextColor(-1) }
  val resultScroll = ScrollView(this); resultScroll.addView(result)
  root.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(150)))
  root.addView(TextView(this).apply { text = "实时日志"; setTextColor(0xFFF0C56B.toInt()) })
  logs = TextView(this).apply { textSize = 11f; setTextColor(0xFFB8C0CC.toInt()); movementMethod = ScrollingMovementMethod() }
  val logScroll = ScrollView(this); logScroll.addView(logs)
  root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
  setContentView(root, ViewGroup.LayoutParams(-1, -1))
 }
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode == 41) ScreenAssistService.log(if (resultCode == RESULT_OK) "用户已授权屏幕捕获；截图管线尚未接入" else "用户取消屏幕捕获授权")
 }
 override fun onResume() { super.onResume(); handler.post(refresh) }
 override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
 private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
 private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
 companion object {
  fun permissionStatus(c: Context): JSONObject {
   val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
   val ok = enabled.split(':').any { it.contains("com.dafu.estimator.ScreenAssistService", true) }
   return JSONObject().put("accessibilityEnabled", ok).put("accessibilityConnected", ScreenAssistService.isConnected())
    .put("overlayAllowed", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))
    .put("screenCapture", "每次启动需系统授权")
  }
 }
}