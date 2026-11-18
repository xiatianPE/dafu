package com.dafu.estimator

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_RECOGNIZE_NOW = "com.dafu.estimator.RECOGNIZE_NOW"
        private const val CHANNEL = "dafu_screen_ocr"
        @Volatile var running = false
        @Volatile var latestJson = JSONObject().put("status", "not_started").toString()
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var ocrEngine: OcrEngine? = null
    private val processing = AtomicBoolean(false)
    private var lastProcessAt = 0L
    private var lastRecognitionSummary = ""
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        worker = HandlerThread("DafuScreenOCR").also { it.start() }
        handler = Handler(worker!!.looper)
        createNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECOGNIZE_NOW) {
            handler?.post {
                val activeReader = reader
                if (activeReader != null && running) {
                    processLatestImage(activeReader, force = true)
                    NativeLog.add("已执行一次手动屏幕识别")
                } else {
                    NativeLog.add("手动识别失败：屏幕捕获尚未就绪")
                }
            }
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            NativeLog.add("OCR 启动失败：缺少屏幕授权数据")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    NativeLog.add("系统已停止屏幕捕获")
                    stopSelf()
                }
            }, handler)
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source -> processLatestImage(source) }, handler)
            display = projection?.createVirtualDisplay(
                "DafuAuctionOCR", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
            )
            running = true
            latestJson = JSONObject()
                .put("status", "running")
                .put("screenWidth", screenWidth)
                .put("screenHeight", screenHeight)
                .put("engine", "PP-OCRv4-mobile/ONNX-Runtime")
                .toString()
            NativeLog.add("屏幕捕获已启动，OCR 引擎将在首帧初始化")
        } catch (e: Exception) {
            NativeLog.add("OCR 初始化失败：" + (e.message ?: e.javaClass.simpleName))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "达芙屏幕识别", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("达芙屏幕识别")
            .setContentText("正在本机离线识别拍卖画面")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                22, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(22, notification)
        }
    }

    private fun processLatestImage(source: ImageReader, force: Boolean = false) {
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val now = System.currentTimeMillis()
        if ((!force && now - lastProcessAt < 1000) || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastProcessAt = now

        var activeImage: Image? = image
        var fullBitmap: Bitmap? = null
        var prepared: PreparedOcrFrame? = null
        try {
            fullBitmap = imageToBitmap(image)
            image.close()
            activeImage = null

            prepared = OcrImagePreprocessor.prepare(
                fullBitmap,
                Store.get("dafu-ocr-region")
            )
            val bitmap = prepared.bitmap
            val engine = ocrEngine ?: RapidOcrEngine(this).also { ocrEngine = it }
            val frame = engine.recognize(bitmap)
            val parsed = AuctionCardRecognizer.parse(
                frame, bitmap.width, bitmap.height, prepared.regionUsed
            )
            latestJson = parsed.toString()

            val cards = parsed.optJSONArray("intelCards")
            val summary = (0 until (cards?.length() ?: 0)).joinToString(" · ") { index ->
                val card = cards?.optJSONObject(index) ?: JSONObject()
                val value = if (card.optBoolean("valueDetected")) card.optString("value") else "未读到数字"
                "\${card.optString("title")}=$value"
            }
            val logSummary = "\${parsed.optString("status")} | $summary"
            if (logSummary != lastRecognitionSummary) {
                NativeLog.add(
                    "PP-OCR：\${parsed.optInt("detectedCount")}/3 张卡片 | " +
                        "\${frame.elapsedSeconds}s | $logSummary"
                )
                lastRecognitionSummary = logSummary
            }
            Store.set("dafu-screen-recognition", latestJson)
        } catch (e: Exception) {
            latestJson = JSONObject()
                .put("source", "rapidocr_ppocrv4")
                .put("engine", "PP-OCRv4-mobile/ONNX-Runtime")
                .put("status", "ocr_error")
                .put("message", e.message ?: e.javaClass.simpleName)
                .put("capturedAt", System.currentTimeMillis())
                .toString()
            Store.set("dafu-screen-recognition", latestJson)
            NativeLog.add("PP-OCR 识别失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { activeImage?.close() } catch (_: Exception) {}
            try {
                if (prepared?.ownsBitmap == true) prepared?.bitmap?.recycle()
            } catch (_: Exception) {}
            try { fullBitmap?.recycle() } catch (_: Exception) {}
            processing.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    override fun onDestroy() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { ocrEngine?.close() } catch (_: Exception) {}
        ocrEngine = null
        worker?.quitSafely()
        NativeLog.add("屏幕识别已停止")
        super.onDestroy()
    }
}
