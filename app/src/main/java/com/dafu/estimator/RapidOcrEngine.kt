package com.dafu.estimator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.OcrConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PP-OCRv4 mobile detector + recognizer through RapidOCR4j Android.
 * Model files are packaged by the Android OCR AAR; inference is local.
 */
class RapidOcrEngine(context: Context) : OcrEngine {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val engine: RapidOCR

    init {
        val config = OcrConfig()
        config.global.isPrintVerbose = false
        config.global.isUseDet = true
        config.global.isUseCls = false // game UI is upright; skip unnecessary classifier
        config.global.isUseRec = true
        config.global.textScore = 0.30f
        config.global.minHeight = 18
        config.global.widthHeightRatio = -1.0f
        config.global.maxSideLen = 2000
        config.global.minSideLen = 18
        config.global.intraOpNumThreads = 2
        config.global.interOpNumThreads = 1
        config.det.boxThresh = 0.35f
        config.det.unclipRatio = 1.6f
        config.rec.intraOpNumThreads = 2
        config.rec.interOpNumThreads = 1
        engine = RapidOCR.create(appContext, config)
    }

    override fun recognize(bitmap: Bitmap): OcrFrameResult {
        check(!closed.get()) { "OCR engine is closed" }
        val result = engine.run(bitmap)
        val regions = result.recRes.orEmpty().mapNotNull { item ->
            val text = item.text?.trim().orEmpty()
            val points = item.dtBoxes
            if (text.isEmpty() || points == null || points.isEmpty()) return@mapNotNull null

            val xs = points.map { it.x }
            val ys = points.map { it.y }
            val bounds = Rect(
                floor(xs.minOrNull() ?: 0.0).toInt(),
                floor(ys.minOrNull() ?: 0.0).toInt(),
                ceil(xs.maxOrNull() ?: 0.0).toInt(),
                ceil(ys.maxOrNull() ?: 0.0).toInt()
            )
            OcrTextRegion(text, item.confidence, bounds)
        }.sortedWith(compareBy<OcrTextRegion> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })

        return OcrFrameResult(
            regions = regions,
            elapsedSeconds = result.elapseTime,
            detectionSeconds = result.detTime,
            recognitionSeconds = result.recTime
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // RapidOCR4j does not currently expose a public close method.
            // Its ONNX sessions are released with the process.
        }
    }
}
