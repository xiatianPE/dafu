package com.dafu.estimator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/** Android requires explicit user consent before any app can capture the screen. */
class ScreenCaptureActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE = 9104
        fun launch(context: Context) {
            context.startActivity(Intent(context, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (e: Exception) {
            NativeLog.add("无法请求屏幕捕获权限：" + (e.message ?: "未知错误"))
            Toast.makeText(this, "无法启动屏幕识别", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Android API; kept for compatibility with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) { finish(); return }
        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                NativeLog.add("已获得屏幕捕获授权，启动 OCR 识别")
            } catch (e: Exception) {
                NativeLog.add("启动 OCR 服务失败：" + (e.message ?: "未知错误"))
            }
        } else {
            NativeLog.add("用户未授权屏幕捕获")
            Toast.makeText(this, "需要允许屏幕共享才能识别游戏画面", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
