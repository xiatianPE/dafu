package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs

/**
 * Reads only the three auction intelligence cards and extracts their live numeric values.
 */
object ScreenRecognition {
    private data class Target(val title: String, val type: String, val unit: String)
    private data class OcrLine(val text: String, val box: Rect)

    private val targets = listOf(
        Target("千眼其一", "auction_info", "件"),
        Target("紫品计数仪器", "purple_count", "件"),
        Target("金品均价仪器", "gold_average", "")
    )

    fun parse(text: Text, width: Int, height: Int): JSONObject {
        val all = mutableListOf<OcrLine>()
        val lineJson = JSONArray()
        for (block in text.textBlocks) for (line in block.lines) {
            val value = line.text.trim()
            if (value.isNotEmpty()) {
                val box = line.boundingBox ?: Rect()
                all += OcrLine(value, box)
                lineJson.put(JSONObject()
                    .put("text", value).put("left", box.left).put("top", box.top)
                    .put("right", box.right).put("bottom", box.bottom)
                    .put("cx", box.centerX()).put("cy", box.centerY()))
            }
        }

        val ordered = all.sortedBy { it.box.centerY() }
        val anchors = targets.map { target ->
            val candidate = ordered.map { line -> line to similarity(normalize(line.text), normalize(target.title)) }
                .filter { it.second >= 0.68 || normalize(it.first.text).contains(normalize(target.title)) }
                .maxByOrNull { it.second }
            target to candidate?.first
        }
        val cards = JSONArray()
        val missing = JSONArray()

        for ((index, pair) in anchors.withIndex()) {
            val (target, anchor) = pair
            if (anchor == null) {
                missing.put(target.title)
                continue
            }
            val anchorY = anchor.box.centerY()
            val nextY = anchors.drop(index + 1).firstNotNullOfOrNull { it.second?.box?.centerY() }
                ?: height
            // Numeric text must be below this card's title and before the next card title.
            val descriptionLines = ordered.filter { line ->
                line.box.centerY() >= anchorY - 2 &&
                    line.box.centerY() < nextY &&
                    line.box.centerY() <= anchorY + height * 0.24f &&
                    line.box.centerX() in (width * 0.12f).toInt()..(width * 0.90f).toInt()
            }
            val description = descriptionLines.joinToString(" ") { it.text }
            val number = Regex("(?<![A-Za-z])([0-9][0-9,]*(?:\\.[0-9]+)?)")
                .find(description)?.groupValues?.getOrNull(1)?.replace(",", "")
            cards.put(JSONObject()
                .put("title", target.title)
                .put("type", target.type)
                .put("detected", true)
                .put("description", description)
                .put("value", number ?: "")
                .put("unit", target.unit)
                .put("valueDetected", number != null)
                .put("y", anchorY))
        }

        val detected = cards.length()
        val valueDetected = (0 until cards.length()).count { cards.optJSONObject(it)?.optBoolean("valueDetected") == true }
        val raw = ordered.joinToString("\\n") { it.text }
        return JSONObject()
            .put("schemaVersion", 4)
            .put("source", "screen_ocr")
            .put("status", when {
                all.isEmpty() -> "no_text_detected"
                detected == targets.size && valueDetected == targets.size -> "all_values_detected"
                detected == targets.size -> "cards_detected_values_incomplete"
                detected > 0 -> "partial_detection"
                else -> "none_of_three_detected"
            })
            .put("screenWidth", width).put("screenHeight", height)
            .put("intelCards", cards)
            .put("detectedCount", detected).put("expectedCount", targets.size)
            .put("valueDetectedCount", valueDetected)
            .put("missingTargets", missing)
            .put("rawText", raw)
            .put("lineCount", ordered.size).put("lines", lineJson)
            .put("capturedAt", System.currentTimeMillis())
            .put("confidence", if (detected == 3 && valueDetected == 3) "high" else if (detected > 0) "medium" else "low")
    }

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), "")
        .replace('臺', '台')
        .replace('兩', '两')
        .lowercase(Locale.ROOT)

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contains(b) || b.contains(a)) return 1.0
        val previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var diagonal = previous[0]
            previous[0] = i + 1
            for (j in b.indices) {
                val above = previous[j + 1]
                previous[j + 1] = minOf(previous[j + 1] + 1, previous[j] + 1, diagonal + if (a[i] == b[j]) 0 else 1)
                diagonal = above
            }
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
