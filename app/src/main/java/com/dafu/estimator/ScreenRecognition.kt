package com.dafu.estimator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.Text
import java.util.Locale

/** Extract only the auction fields needed by Dafu; preserve raw OCR for manual verification. */
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
                        .put("left", box.left)
                        .put("top", box.top)
                        .put("right", box.right)
                        .put("bottom", box.bottom)
                        .put("cx", (box.left + box.right) / 2)
                        .put("cy", (box.top + box.bottom) / 2)
                }
            }
        }
        val joined = all.joinToString("\n") { it.first }
        val roundRegex = Regex("竞拍第\\s*([0-9一二三四五六七八九十]+)\\s*回合")
        val roundMatch = roundRegex.find(joined)
        val round = roundMatch?.groupValues?.getOrNull(1)?.let(::toRound)
        val cards = JSONArray()
        val known = listOf("拍卖师公开情报", "千眼其一", "紫品计数仪器", "金品均价仪器")
        val centerLines = all.filter { (_, r) -> r.centerX() in (width * 0.28f).toInt()..(width * 0.72f).toInt() }
        for ((i, pair) in centerLines.withIndex()) {
            val title = pair.first
            if (known.any { title.contains(it) }) {
                val body = centerLines.drop(i + 1).takeWhile { (_, r) ->
                    kotlin.math.abs(r.centerY() - pair.second.centerY()) < height * 0.24
                }.joinToString(" ") { it.first }
                cards.put(JSONObject().put("title", title).put("description", body)
                    .put("y", pair.second.centerY()).put("type", when {
                        title.contains("千眼其一") -> "auction_info"
                        title.contains("紫品计数仪器") -> "purple_count"
                        title.contains("金品均价仪器") -> "gold_average"
                        else -> "public_info"
                    }))
            }
        }
        val lowerRight = all.filter { (_, r) -> r.centerY() > height * 0.65f && r.centerX() > width * 0.55f }
        val bidButton = lowerRight.any { it.first.replace(" ", "").contains("出价") && !it.first.contains("上轮出价") }
        val giveUpButton = lowerRight.any { it.first.contains("放弃") }
        val keypad = all.any { it.first.contains("确认出价") } &&
            all.any { it.first.contains("清空") } &&
            all.any { it.first.contains("上轮出价") }
        val amountPattern = Regex("(?:当前估价|推荐出价参考|可输入范围|愿意出的价格)[：:]?\\s*([0-9][0-9,，.万]*)")
        val amounts = JSONArray()
        for (m in amountPattern.findAll(joined)) {
            amounts.put(JSONObject().put("label", m.groupValues[0].replace(Regex("[:：].*"), ""))
                .put("value", m.groupValues[1].replace(",", "").replace("，", "")))
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("source", "screen_ocr")
            .put("screenWidth", width).put("screenHeight", height)
            .put("round", round ?: JSONObject.NULL)
            .put("roundText", roundMatch?.value ?: "")
            .put("intelCards", cards)
            .put("bidButtonVisible", bidButton)
            .put("giveUpButtonVisible", giveUpButton)
            .put("bidKeyboardVisible", keypad)
            .put("amounts", amounts)
            .put("rawText", joined)
            .put("lines", lines)
            .put("capturedAt", System.currentTimeMillis())
            .put("confidence", confidence(round != null, cards.length(), bidButton || giveUpButton, keypad))
    }

    private fun confidence(round: Boolean, cards: Int, buttons: Boolean, keypad: Boolean): String = when {
        round && cards > 0 && buttons -> "high"
        round || cards > 0 || buttons || keypad -> "medium"
        else -> "low"
    }

    private fun toRound(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        return when (value) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
            else -> null
        }
    }
}
