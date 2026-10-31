package com.dafu.estimator

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object NativeLog {
    private val entries = CopyOnWriteArrayList<String>()
    @Volatile private var lastEvent = ""
    @Volatile private var lastEventAt = 0L

    fun add(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastEvent && now - lastEventAt < 1200L) return
        lastEvent = message
        lastEventAt = now
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        entries.add(0, "$stamp  $message")
        while (entries.size > 80) entries.removeAt(entries.lastIndex)
    }

    fun snapshot(limit: Int = 30): List<String> = entries.take(limit)
}