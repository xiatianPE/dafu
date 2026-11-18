package com.dafu.estimator

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent native event log.
 * OCR raw-output entries are never deduplicated, and remain available after
 * the screen-capture service stops or the app process is recreated.
 */
object NativeLog {
    private const val PREFS = "dafu_native_logs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50
    private val entries = CopyOnWriteArrayList<String>()
    @Volatile private var lastEvent = ""
    @Volatile private var lastEventAt = 0L
    @Volatile private var initialized = false
    @Volatile private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs?.getString(KEY_ENTRIES, null)
        if (!saved.isNullOrBlank()) {
            try {
                val array = JSONArray(saved)
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) entries.add(value)
                }
            } catch (_: Exception) { }
        }
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        initialized = true
    }

    @Synchronized
    fun add(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastEvent && now - lastEventAt < 1200L) return
        lastEvent = message
        lastEventAt = now
        append(formatStamp(now) + "  " + message)
    }

    /** Add a complete OCR frame without duplicate suppression. */
    @Synchronized
    fun addRawOcr(message: String) {
        val now = System.currentTimeMillis()
        append(formatStamp(now) + "  " + message)
    }

    private fun append(entry: String) {
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        val array = JSONArray()
        entries.forEach { array.put(it) }
        // commit() makes the diagnostic log durable before the capture service exits.
        prefs?.edit()?.putString(KEY_ENTRIES, array.toString())?.commit()
    }

    private fun formatStamp(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))

    @Synchronized
    fun snapshot(limit: Int = 30): List<String> = entries.take(limit)

    @Synchronized
    fun clear() {
        entries.clear()
        prefs?.edit()?.remove(KEY_ENTRIES)?.commit()
        lastEvent = ""
        lastEventAt = 0L
    }
}
