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
        config.getGlobal().setPrintVerbose(false)
        config.getGlobal().setUseDet(true)
        config.getGlobal().setUseCls(false) // game UI is upright; skip unnecessary classifier
        config.getGlobal().setUseRec(true)
        config.getGlobal().setTextScore(0.30f)
        config.getGlobal().setMinHeight(18)
        config.getGlobal().setWidthHeightRatio(-1.0f)
        config.getGlobal().setMaxSideLen(2000)
        config.getGlobal().setMinSideLen(18)
        config.getGlobal().setIntraOpNumThreads(2)
        config.getGlobal().setInterOpNumThreads(1)
        config.getDet().setBoxThresh(0.35f)
        config.getDet().setUnclipRatio(1.6f)
        config.getRec().setIntraOpNumThreads(2)
        config.getRec().setInterOpNumThreads(1)
        engine = RapidOCR.create(appContext, config)
    }

    override fun recognize(bitmap: Bitmap): OcrFrameResult {
        check(!closed.get()) { "OCR engine is closed" }
        val result = engine.run(bitmap)
        val regions = result.getRecRes().orEmpty().mapNotNull { item ->
            val text = item.getText()?.trim().orEmpty()
            val points = item.getDtBoxes()
            if (text.isEmpty() || points == null || points.isEmpty()) return@mapNotNull null

            val xs = points.map { it.x }
            val ys = points.map { it.y }
            val bounds = Rect(
                floor(xs.minOrNull() ?: 0.0).toInt(),
                floor(ys.minOrNull() ?: 0.0).toInt(),
                ceil(xs.maxOrNull() ?: 0.0).toInt(),
                ceil(ys.maxOrNull() ?: 0.0).toInt()
            )
            OcrTextRegion(text, item.getConfidence(), bounds)
        }.sortedWith(compareBy<OcrTextRegion> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })

        return OcrFrameResult(
            regions = regions,
            elapsedSeconds = result.getElapseTime(),
            detectionSeconds = result.getDetTime(),
            recognitionSeconds = result.getRecTime()
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // RapidOCR4j does not currently expose a public close method.
            // Its ONNX sessions are released with the process.
        }
    }
}
