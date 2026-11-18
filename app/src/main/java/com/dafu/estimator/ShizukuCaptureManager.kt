package com.dafu.estimator

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

/** One-shot Shizuku screenshot test; does not replace MediaProjection yet. */
object ShizukuCaptureManager {
    private const val REQUEST_CODE = 7301
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun capture(context: Context, callback: (String) -> Unit) {
        val app = context.applicationContext
        try {
            if (!Shizuku.pingBinder()) {
                callback("Shizuku 服务未运行。请先通过无线调试启动服务。")
                return
            }
            val uid = Shizuku.getUid()
            if (uid != 2000) {
                callback("当前 Shizuku UID=" + uid + "，不是预期的 ADB Shell UID 2000。")
                return
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
                callback("已请求达芙的 Shizuku 授权；授权后请再次点击测试截图。")
                return
            }

            val args = Shizuku.UserServiceArgs(
                ComponentName(app, DafuShizukuUserService::class.java)
            ).daemon(false).version(1).processNameSuffix("dafu_capture")

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val remote = IDafuShizukuService.Stub.asInterface(binder)
                    val connectionRef = this
                    worker.execute {
                        val result = try {
                            val shellUid = remote.shellUid
                            if (shellUid != 2000) {
                                "ERROR: UserService UID=" + shellUid + ", expected 2000"
                            } else {
                                val response = remote.captureScreen()
                                if (!response.startsWith("OK:")) {
                                    response
                                } else {
                                    val parts = response.split(":", limit = 3)
                                    val file = File(parts[1])
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                    if (!file.exists() || file.length() <= 0L || options.outWidth <= 0 || options.outHeight <= 0) {
                                        "ERROR: screenshot file cannot be decoded"
                                    } else {
                                        "成功：Shizuku Shell UID=" + shellUid + "；截图 " +
                                            options.outWidth + "×" + options.outHeight + "；" + file.length() + " bytes"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            "ERROR: " + e.javaClass.simpleName + ": " + (e.message ?: "unknown")
                        }
                        try { Shizuku.unbindUserService(args, connectionRef, true) } catch (_: Exception) {}
                        main.post { callback(result) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    main.post { callback("Shizuku 截图服务连接断开") }
                }
            }
            Shizuku.bindUserService(args, connection)
            callback("正在通过 Shizuku Shell 启动截图服务…")
        } catch (e: Exception) {
            callback("Shizuku 调用失败：" + e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
        }
    }
}
