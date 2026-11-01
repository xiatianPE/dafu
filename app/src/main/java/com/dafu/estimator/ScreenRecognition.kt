package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.Text
import java.util.Locale

/**
 * Focused recognition for the three requested auction intelligence items only.
 * OCR text is whitespace-normalized so titles split across lines can still match.
 */
object ScreenRecognition {
    private data class Target(val title: String, val type: String, val aliases: List<String>)

    private val targets = listOf(
        Target("千眼其一", "auction_info", listOf("千眼其一")),
        Target("紫品计数仪器", "purple_count", listOf("紫品计数仪器", "紫品计数仪器")),
        Target("金品均价仪器", "gold_average", listOf("金品均价仪器", "金品均价仪器"))
    )

    fun parse(text: Text, width: Int, height: Int): JSONObject {
        val lines = mutableListOf<JSONObject>()
        val all = mutableListOf<Pair<String, Rect>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: Rect()
                val value = line.text.trim()
                if (value.isNotEmpty()) {
                    all += value to box
                    lines += JSONObject()
                        .put("text", value)
                        .put("left", box.left).put("top", box.top)
                        .put("right", box.right).put("bottom", box.bottom)
                        .put("cx", box.centerX()).put("cy", box.centerY())
                }
            }
        }

        val raw = all.joinToString("\n") { it.first }
        val normalized = normalize(raw)
        val cards = JSONArray()
        val matchedTypes = mutableListOf<String>()

        for (target in targets) {
            val matchedAlias = target.aliases.firstOrNull { normalized.contains(normalize(it)) }
            if (matchedAlias != null) {
                matchedTypes += target.type
                val titleNorm = normalize(matchedAlias)
                val anchor = all.firstOrNull { normalize(it.first).contains(titleNorm) }
                val y = anchor?.second?.centerY() ?: -1
                // Keep a small local text excerpt for diagnostics only; matching is based on title.
                val description = if (anchor != null) {
                    all.filter { (_, box) ->
                        box.centerX() in (width * 0.25f).toInt()..(width * 0.75f).toInt() &&
                            kotlin.math.abs(box.centerY() - y) < height * 0.07
                    }.joinToString(" ") { it.first }
                } else ""
                cards.put(JSONObject()
                    .put("title", target.title)
                    .put("type", target.type)
                    .put("detected", true)
                    .put("description", description)
                    .put("y", y))
            }
        }

        val missing = JSONArray()
        for (target in targets) if (target.type !in matchedTypes) missing.put(target.title)

        return JSONObject()
            .put("schemaVersion", 3)
            .put("source", "screen_ocr")
            .put("status", when {
                all.isEmpty() -> "no_text_detected"
                cards.length() == targets.size -> "all_three_detected"
                cards.length() > 0 -> "partial_detection"
                else -> "none_of_three_detected"
            })
            .put("screenWidth", width)
            .put("screenHeight", height)
            .put("intelCards", cards)
            .put("detectedCount", cards.length())
            .put("expectedCount", targets.size)
            .put("missingTargets", missing)
            .put("rawText", raw)
            .put("normalizedText", normalized)
            .put("lineCount", all.size)
            .put("lines", lines)
            .put("capturedAt", System.currentTimeMillis())
            .put("confidence", when {
                cards.length() == targets.size -> "high"
                cards.length() > 0 -> "medium"
                all.isNotEmpty() -> "low"
                else -> "none"
            })
    }

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), "")
        .replace('臺', '台')
        .replace('兩', '两')
        .lowercase(Locale.ROOT)
}
