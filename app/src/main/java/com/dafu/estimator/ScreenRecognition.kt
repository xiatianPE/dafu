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

        // Read values from the description belonging to each card, not from
        // arbitrary nearby HUD text. The three cards have distinctive Chinese
        // description phrases; those phrases are stronger evidence than distance.
        val cards = JSONArray()
        val missingValues = JSONArray()

        for (anchor in anchors) {
            val target = anchor.target
            val localLines = ordered.filter { line ->
                if (line == anchor.line) return@filter false
                val dx = abs(line.box.centerX() - anchor.line.box.centerX()).toDouble()
                val dy = line.box.centerY() - anchor.line.box.centerY().toDouble()
                // Description is below the title in the card. Tight bounds prevent
                // values from the inventory, top HUD and neighboring cards leaking in.
                dx <= (safeWidth * 0.18) && dy in 0.0..(safeHeight * 0.14)
            }

            fun isTargetDescription(value: String): Boolean {
                val n = normalize(value)
                return when (target.type) {
                    "auction_info" ->
                        n.contains("总件数") || n.contains("总数量") ||
                        (n.contains("本局内") && n.contains("藏品") && n.contains("件"))
                    "purple_count" ->
                        (n.contains("紫色品质") || n.contains("紫品")) &&
                        (n.contains("总数量") || n.contains("总件数") || n.contains("数量"))
                    "gold_average" ->
                        (n.contains("金色品质") || n.contains("金品")) &&
                        (n.contains("平均价值") || n.contains("平均价") || n.contains("均价"))
                    else -> false
                }
            }

            val matchingDescription = localLines
                .filter { isTargetDescription(it.text) }
                .sortedBy { abs(it.box.centerY() - anchor.line.box.centerY()) }
            val bestLine = matchingDescription.firstOrNull()
            val bestNumber = bestLine?.let { line ->
                numberPattern.findAll(line.text)
                    .map { it.groupValues[1].replace(",", "") }
                    .firstOrNull()
            }
            if (bestNumber == null) missingValues.put(target.title)

            val description = (listOf(anchor.line) + localLines)
                .sortedWith(compareBy<OcrLine> { it.box.centerY() }.thenBy { it.box.centerX() })
                .joinToString(" ") { it.text }

            cards.put(
                JSONObject()
                    .put("title", target.title)
                    .put("type", target.type)
                    .put("detected", true)
                    .put("anchorText", anchor.line.text)
                    .put("description", description)
                    .put("matchedDescription", bestLine?.text ?: "")
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
