package com.dafu.estimator

import android.content.Context
import android.content.SharedPreferences

/**
 * 原生侧的数据中心。网页里的 dafu-auction-v3（估价状态 JSON）、dafu-theme 等键都存在这里。
 * 主界面和悬浮窗两个 WebView 共用它；原生代码也可以直接 Store.get("dafu-auction-v3") 读取。
 * version 每次数据真正变化时 +1，用来判断另一个 WebView 是否需要刷新。
 */
object Store {
    private lateinit var sp: SharedPreferences

    @Volatile
    var version: Long = 0L
        private set

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("dafu_store", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun get(k: String): String? = sp.getString(k, null)

    @Synchronized
    fun set(k: String, v: String) {
        if (sp.getString(k, null) == v) return
        sp.edit().putString(k, v).apply()
        version++
    }

    @Synchronized
    fun remove(k: String) {
        if (!sp.contains(k)) return
        sp.edit().remove(k).apply()
        version++
    }
}
