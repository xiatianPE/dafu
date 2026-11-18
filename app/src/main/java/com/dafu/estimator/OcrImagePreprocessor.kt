package com.dafu.estimator

import android.graphics.Bitmap
import org.json.JSONObject

data class PreparedOcrFrame(
    val bitmap: Bitmap,
    val regionUsed: Boolean,
    val ownsBitmap: Boolean
)

/**
 * Applies the saved normalized selection without changing the coordinate system
 * inside the selected frame. The caller owns and must recycle the returned crop.
 */
object OcrImagePreprocessor {
    fun prepare(source: Bitmap, regionJson: String?): PreparedOcrFrame {
        if (regionJson.isNullOrBlank()) {
            return PreparedOcrFrame(source, false, false)
        }

        return try {
            val region = JSONObject(regionJson)
            val left = (region.optDouble("left", 0.0) * source.width)
                .toInt().coerceIn(0, source.width - 1)
            val top = (region.optDouble("top", 0.0) * source.height)
                .toInt().coerceIn(0, source.height - 1)
            val right = (region.optDouble("right", 1.0) * source.width)
                .toInt().coerceIn(left + 1, source.width)
            val bottom = (region.optDouble("bottom", 1.0) * source.height)
                .toInt().coerceIn(top + 1, source.height)

            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            if (crop === source) PreparedOcrFrame(source, false, false)
            else PreparedOcrFrame(crop, true, true)
        } catch (_: Exception) {
            PreparedOcrFrame(source, false, false)
        }
    }
}
