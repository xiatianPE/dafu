package com.dafu.estimator

import android.graphics.Bitmap
import android.graphics.Rect

/** A single OCR text region, expressed in the input bitmap's pixel coordinates. */
data class OcrTextRegion(
    val text: String,
    val confidence: Float,
    val bounds: Rect
)

data class OcrFrameResult(
    val regions: List<OcrTextRegion>,
    val elapsedSeconds: Double,
    val detectionSeconds: Double,
    val recognitionSeconds: Double
)

interface OcrEngine : java.io.Closeable {
    fun recognize(bitmap: Bitmap): OcrFrameResult
}
