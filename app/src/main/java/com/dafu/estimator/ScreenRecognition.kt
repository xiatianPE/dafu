package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.Text
import java.util.Locale

/**
 * Parses the auction UI from Chinese OCR. OCR can split one visual phrase into
 * several lines/blocks, so matching is done on both original lines and a
 * whitespace-free normalized string. Never trigger actions from this result.
 */
object ScreenRecognition {
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
                        .put("cx", (box.left + box.right) / 2)
                        .put("cy", (box.top + box.bottom) / 2)
                }
            }
        }

        val raw = all.joinToString("\n") { it.first }
        val normalized = normalize(raw)
        val roundRegex = Regex("竞拍第([0-9一二两三四五六七八九十]+)回合")
        val roundMatch = roundRegex.find(normalized)
        val round = roundMatch?.groupValues?.getOrNull(1)?.let(::toRound)

        // Match known cards against the full screen text, not a single OCR line.
        // ML Kit often splits a title such as “金品均价仪器” into multiple lines.
        val cardDefs = listOf(
            Triple("拍卖师公开情报", "public_info", listOf("拍卖师公开情报", "拍卖师公开情报")),
            Triple("千眼其一", "auction_info", listOf("千眼其一", "千眼其一")),
            Triple("紫品计数仪器", "purple_count", listOf("紫品计数仪器", "紫品计数仪器")),
            Triple("金品均价仪器", "gold_average", listOf("金品均价仪器", "金品均价仪器"))
        )
        val cards = JSONArray()
        for ((title, type, variants) in cardDefs) {
            if (variants.any { normalized.contains(normalize(it)) }) {
                val anchor = all.firstOrNull { normalize(it.first).contains(normalize(title)) }
                val y = anchor?.second?.centerY() ?: -1
                val desc = if (anchor != null) {
                    all.filter { (_, box) -> box.centerX() in (width * 0.25f).toInt()..(width * 0.75f).toInt() &&
                        kotlin.math.abs(box.centerY() - y) < height * 0.12 }
                        .joinToString(" ") { it.first }
                } else ""
                cards.put(JSONObject().put("title", title).put("description", desc)
                    .put("y", y).put("type", type))
            }
        }

        val normalizedLines = all.map { normalize(it.first) }
        val hasConfirm = normalized.contains("确认出价")
        val hasClear = normalized.contains("清空")
        val hasPrevious = normalized.contains("上轮出价")
        val keypad = hasConfirm && (hasClear || hasPrevious)
        val giveUp = normalized.contains("放弃")
        // On the normal auction screen, the bottom-right “出价” is a button.
        // During keypad entry, “确认出价” is a separate control.
        val bid = !keypad && normalizedLines.any { it == "出价" || it.contains("出价") } &&
            !normalized.contains("输入你愿意出的价格")
        val amounts = JSONArray()
        val amountRegex = Regex("(当前估价|推荐出价参考|可输入范围|愿意出的价格)[:：]?([0-9][0-9,，.万]*)")
        for (m in amountRegex.findAll(normalized)) {
            amounts.put(JSONObject().put("label", m.groupValues[1]).put("value", m.groupValues[2].replace(",", "").replace("，", "")))
        }

        val confidence = when {
            round != null && cards.length() > 0 && (bid || giveUp || keypad) -> "high"
            round != null || cards.length() > 0 || bid || giveUp || keypad -> "medium"
            all.isNotEmpty() -> "low"
            else -> "none"
        }
        return JSONObject()
            .put("schemaVersion", 2)
            .put("source", "screen_ocr")
            .put("status", if (all.isEmpty()) "no_text_detected" else "recognized")
            .put("screenWidth", width).put("screenHeight", height)
            .put("round", round ?: JSONObject.NULL)
            .put("roundText", roundMatch?.value ?: "")
            .put("intelCards", cards)
            .put("bidButtonVisible", bid)
            .put("giveUpButtonVisible", giveUp)
            .put("bidKeyboardVisible", keypad)
            .put("confirmBidButtonVisible", hasConfirm)
            .put("amounts", amounts)
            .put("rawText", raw)
            .put("normalizedText", normalized)
            .put("lineCount", all.size)
            .put("lines", lines)
            .put("capturedAt", System.currentTimeMillis())
            .put("confidence", confidence)
    }

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), "")
        .replace('臺', '台')
        .replace('兩', '两')
        .lowercase(Locale.ROOT)

    private fun toRound(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        return when (value) {
            "一" -> 1; "二", "两" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
            else -> null
        }
    }
}
