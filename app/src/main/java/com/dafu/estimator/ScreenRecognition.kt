package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the three auction intelligence cards from a full-screen OCR result.
 *
 * No fixed screen coordinates are used: card titles are detected first, then
 * nearby OCR lines are assigned to the closest detected title by geometry.
 */
object ScreenRecognition {
    private data class Target(val title: String, val type: String, val unit: String)
    private data class OcrLine(val text: String, val box: Rect)
    private data class Anchor(val target: Target, val line: OcrLine)
    private data class NearbyLine(val line: OcrLine, val distance: Double)

    private val targets = listOf(
        Target("千眼其一", "auction_info", "件"),
        Target("紫品计数仪器", "purple_count", "件"),
        Target("金品均价仪器", "gold_average", "")
    )

    private val numberPattern = Regex("(?<![A-Za-z])([0-9][0-9,]*(?:\\.[0-9]+)?)")

    fun parse(text: Text, width: Int, height: Int): JSONObject {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val all = mutableListOf<OcrLine>()
        val lineJson = JSONArray()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val value = line.text.trim()
                if (value.isNotEmpty()) {
                    val box = line.boundingBox ?: Rect()
                    val item = OcrLine(value, box)
                    all += item
                    lineJson.put(
                        JSONObject()
                            .put("text", value)
                            .put("left", box.left).put("top", box.top)
                            .put("right", box.right).put("bottom", box.bottom)
                            .put("cx", box.centerX()).put("cy", box.centerY())
                    )
                }
            }
        }

        val ordered = all.sortedWith(compareBy<OcrLine> { it.box.centerY() }.thenBy { it.box.centerX() })

        // Locate each card by its title anywhere on the captured screen.
        // A title line can only be assigned to one card.
        val usedAnchorLines = mutableSetOf<OcrLine>()
        val anchors = mutableListOf<Anchor>()
        val missing = JSONArray()

        for (target in targets) {
            val targetText = normalize(target.title)
            val candidate = ordered
                .asSequence()
                .filter { it !in usedAnchorLines }
                .map { line -> line to similarity(normalize(line.text), targetText) }
                .filter { (line, score) ->
                    score >= 0.68 || normalize(line.text).contains(targetText)
                }
                .maxByOrNull { it.second }

            if (candidate == null) {
                missing.put(target.title)
            } else {
                usedAnchorLines += candidate.first
                anchors += Anchor(target, candidate.first)
            }
        }

        // Assign every non-title line to the nearest detected card title.
        // This supports horizontal, vertical and mixed card layouts without
        // relying on a fixed x/y band or assuming the next card is below.
        val assigned = anchors.associateWith { mutableListOf<NearbyLine>() }
        for (line in ordered) {
            if (line in usedAnchorLines || anchors.isEmpty()) continue
            val nearest = anchors
                .map { anchor -> anchor to spatialDistance(line, anchor.line, safeWidth, safeHeight) }
                .minByOrNull { it.second }
                ?: continue

            // A line outside this adaptive neighborhood is probably unrelated
            // game HUD text, a button, or text from another part of the screen.
            if (nearest.second <= 1.55) {
                assigned[nearest.first]?.add(NearbyLine(line, nearest.second))
            }
        }

        val cards = JSONArray()
        val missingValues = JSONArray()

        for (anchor in anchors) {
            val target = anchor.target
            val nearby = assigned[anchor].orEmpty()
                .sortedWith(compareBy<NearbyLine> { it.line.box.centerY() }.thenBy { it.line.box.centerX() })
            val description = nearby.joinToString(" ") { it.line.text }

            val numericCandidates = nearby.mapNotNull { item ->
                val match = numberPattern.find(item.line.text) ?: return@mapNotNull null
                val value = match.groupValues[1].replace(",", "")
                val hintBonus = semanticHintBonus(target, item.line.text)
                Triple(item, value, item.distance - hintBonus)
            }
            val bestNumber = numericCandidates.minByOrNull { it.third }?.second
            if (bestNumber == null) missingValues.put(target.title)

            cards.put(
                JSONObject()
                    .put("title", target.title)
                    .put("type", target.type)
                    .put("detected", true)
                    .put("anchorText", anchor.line.text)
                    .put("description", description)
                    .put("value", bestNumber ?: "")
                    .put("unit", target.unit)
                    .put("valueDetected", bestNumber != null)
                    .put("x", anchor.line.box.centerX())
                    .put("y", anchor.line.box.centerY())
            )
        }

        val detected = anchors.size
        val valueDetected = (0 until cards.length()).count {
            cards.optJSONObject(it)?.optBoolean("valueDetected") == true
        }
        val raw = ordered.joinToString("\n") { it.text }

        return JSONObject()
            .put("schemaVersion", 5)
            .put("source", "full_screen_ocr")
            .put(
                "status",
                when {
                    all.isEmpty() -> "no_text_detected"
                    detected == targets.size && valueDetected == targets.size -> "all_values_detected"
                    detected == targets.size -> "cards_detected_values_incomplete"
                    detected > 0 -> "partial_detection"
                    else -> "none_of_three_detected"
                }
            )
            .put("screenWidth", safeWidth).put("screenHeight", safeHeight)
            .put("intelCards", cards)
            .put("detectedCount", detected).put("expectedCount", targets.size)
            .put("valueDetectedCount", valueDetected)
            .put("missingTargets", missing)
            .put("missingValues", missingValues)
            .put("rawText", raw)
            .put("lineCount", ordered.size).put("lines", lineJson)
            .put("capturedAt", System.currentTimeMillis())
            .put(
                "confidence",
                if (detected == targets.size && valueDetected == targets.size) "high"
                else if (detected > 0) "medium" else "low"
            )
    }

    /**
     * Normalized geometric distance. The wider horizontal radius allows cards
     * laid out side-by-side; the vertical radius supports stacked card layouts.
     */
    private fun spatialDistance(line: OcrLine, anchor: OcrLine, width: Int, height: Int): Double {
        val dx = abs(line.box.centerX() - anchor.box.centerX()).toDouble() / (width * 0.30).coerceAtLeast(1.0)
        val dy = abs(line.box.centerY() - anchor.box.centerY()).toDouble() / (height * 0.22).coerceAtLeast(1.0)
        val aboveTitlePenalty = if (line.box.centerY() < anchor.box.centerY()) 0.18 else 0.0
        return sqrt(dx * dx + dy * dy) + aboveTitlePenalty
    }

    private fun semanticHintBonus(target: Target, text: String): Double {
        val value = normalize(text)
        val hints = when (target.type) {
            "auction_info" -> listOf("总件数", "藏品", "品质", "件")
            "purple_count" -> listOf("紫色", "紫品", "计数", "件")
            "gold_average" -> listOf("均价", "平均", "金色", "金品", "价格")
            else -> emptyList()
        }
        return when {
            hints.any { value.contains(normalize(it)) } -> 0.28
            target.unit.isNotEmpty() && text.contains(target.unit) -> 0.16
            else -> 0.0
        }
    }

    private fun normalize(value: String): String = value
        .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
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
                previous[j + 1] = minOf(
                    previous[j + 1] + 1,
                    previous[j] + 1,
                    diagonal + if (a[i] == b[j]) 0 else 1
                )
                diagonal = above
            }
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
