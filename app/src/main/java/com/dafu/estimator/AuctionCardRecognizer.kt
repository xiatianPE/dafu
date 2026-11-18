package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Domain parser only: consumes generic OCR regions and identifies the three
 * auction intelligence cards. It has no dependency on ML Kit or ONNX APIs.
 */
object AuctionCardRecognizer {
    private data class Target(val title: String, val type: String, val unit: String)
    private data class PhraseMatch(val text: String, val centerY: Double, val score: Int)

    private val targets = listOf(
        Target("千眼其一", "auction_info", "件"),
        Target("紫品计数仪器", "purple_count", "件"),
        Target("金品均价仪器", "gold_average", "")
    )
    private val numberPattern = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)")

    fun parse(frame: OcrFrameResult, width: Int, height: Int, regionUsed: Boolean): JSONObject {
        val lines = frame.regions.filter { it.text.isNotBlank() }
        val ordered = lines.sortedWith(compareBy<OcrTextRegion> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
        val lineJson = JSONArray()
        ordered.forEach { line ->
            val b = line.bounds
            lineJson.put(JSONObject()
                .put("text", line.text)
                .put("confidence", line.confidence)
                .put("left", b.left).put("top", b.top)
                .put("right", b.right).put("bottom", b.bottom)
                .put("cx", b.centerX()).put("cy", b.centerY())
                .put("width", b.width()).put("height", b.height()))
        }

        val used = mutableSetOf<OcrTextRegion>()
        val anchors = mutableListOf<Pair<Target, OcrTextRegion>>()
        val missing = JSONArray()
        for (target in targets) {
            val expected = normalize(target.title)
            val candidate = ordered.asSequence()
                .filter { it !in used }
                .map { it to similarity(normalize(it.text), expected) }
                .filter { (line, score) -> score >= 0.68 || normalize(line.text).contains(expected) }
                .maxByOrNull { it.second }?.first
                ?: ordered.firstOrNull { it !in used && isDescription(target, it.text) }

            if (candidate == null) missing.put(target.title)
            else {
                used += candidate
                anchors += target to candidate
            }
        }

        val cards = JSONArray()
        val missingValues = JSONArray()
        for ((target, anchor) in anchors) {
            val nearby = ordered.filter { line ->
                if (line === anchor) return@filter true
                val dx = abs(line.bounds.centerX() - anchor.bounds.centerX())
                val dy = line.bounds.centerY() - anchor.bounds.centerY()
                dx <= (width * 0.38) && dy in (-height * 0.04)..(height * 0.34)
            }
            val phraseMatches = mutableListOf<PhraseMatch>()
            for (i in nearby.indices) {
                var joined = ""
                for (j in i..minOf(nearby.lastIndex, i + 3)) {
                    joined += nearby[j].text
                    if (isDescription(target, joined)) {
                        phraseMatches += PhraseMatch(
                            joined,
                            (nearby[i].bounds.centerY() + nearby[j].bounds.centerY()) / 2.0,
                            joined.length
                        )
                    }
                }
            }
            val phrase = phraseMatches.maxByOrNull { it.score }
            val numberCandidates = nearby.flatMap { line ->
                numberPattern.findAll(line.text).map { match ->
                    Triple(line, match.groupValues[1].replace(",", ""), line.confidence)
                }.toList()
            }
            val selected = if (phrase != null) {
                numberCandidates.minByOrNull { abs(it.first.bounds.centerY() - phrase.centerY) }
            } else {
                val joined = nearby.joinToString("") { it.text }
                if (isDescription(target, joined)) numberCandidates.firstOrNull() else null
            }
            val value = selected?.second
            if (value == null) missingValues.put(target.title)

            val neighborhood = nearby.joinToString(" ") { it.text }
            cards.put(JSONObject()
                .put("title", target.title).put("type", target.type)
                .put("detected", true).put("anchorText", anchor.text)
                .put("description", neighborhood)
                .put("matchedDescription", phrase?.text ?: "")
                .put("value", value ?: "").put("unit", target.unit)
                .put("valueDetected", value != null)
                .put("valueConfidence", selected?.third ?: 0f)
                .put("x", anchor.bounds.centerX()).put("y", anchor.bounds.centerY()))
        }

        val valueCount = (0 until cards.length()).count { cards.optJSONObject(it)?.optBoolean("valueDetected") == true }
        val detected = anchors.size
        val status = when {
            ordered.isEmpty() -> "no_text_detected"
            detected == targets.size && valueCount == targets.size -> "all_values_detected"
            detected == targets.size -> "cards_detected_values_incomplete"
            detected > 0 -> "partial_detection"
            else -> "none_of_three_detected"
        }
        return JSONObject()
            .put("schemaVersion", 6).put("source", "rapidocr_ppocrv4")
            .put("status", status)
            .put("screenWidth", width).put("screenHeight", height)
            .put("intelCards", cards)
            .put("detectedCount", detected).put("expectedCount", targets.size)
            .put("valueDetectedCount", valueCount)
            .put("missingTargets", missing).put("missingValues", missingValues)
            .put("rawText", ordered.joinToString("\n") { it.text })
            .put("lineCount", ordered.size).put("lines", lineJson)
            .put("regionUsed", regionUsed)
            .put("engine", "PP-OCRv4-mobile/ONNX-Runtime")
            .put("timing", JSONObject()
                .put("totalSeconds", frame.elapsedSeconds)
                .put("detectionSeconds", frame.detectionSeconds)
                .put("recognitionSeconds", frame.recognitionSeconds))
            .put("capturedAt", System.currentTimeMillis())
            .put("confidence", if (detected == targets.size && valueCount == targets.size) "high" else if (detected > 0) "medium" else "low")
    }

    private fun isDescription(target: Target, value: String): Boolean {
        val n = normalize(value)
        return when (target.type) {
            "auction_info" -> n.contains("总件数") || n.contains("总数量") ||
                (n.contains("本局内") && n.contains("藏品") && n.contains("件"))
            "purple_count" -> (n.contains("紫色品质") || n.contains("紫品")) &&
                (n.contains("总数量") || n.contains("总件数") || n.contains("数量"))
            "gold_average" -> (n.contains("金色品质") || n.contains("金品")) &&
                (n.contains("平均价值") || n.contains("平均价") || n.contains("均价"))
            else -> false
        }
    }

    private fun normalize(value: String): String = value
        .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        .replace('臺', '台').replace('兩', '两').lowercase(Locale.ROOT)

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contains(b) || b.contains(a)) return 1.0
        val previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var diagonal = previous[0]
            previous[0] = i + 1
            for (j in b.indices) {
                val above = previous[j + 1]
                previous[j + 1] = minOf(previous[j + 1] + 1, previous[j] + 1,
                    diagonal + if (a[i] == b[j]) 0 else 1)
                diagonal = above
            }
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
