package com.dafu.estimator

import android.webkit.JavascriptInterface

/** 暴露给网页的 window.Native。每个 WebView 一个实例，seen 记录它最后一次见到的数据版本。 */
class Bridge {
    @Volatile
    var seen: Long = Store.version

    /** 别的 WebView 改过数据，本页还是旧的 */
    fun stale(): Boolean = seen != Store.version

    @JavascriptInterface
    fun get(k: String): String? = if (ok(k)) Store.get(k) else null

    @JavascriptInterface
    fun set(k: String, v: String) {
        if (ok(k) && v.length <= 1_000_000) {
            Store.set(k, v)
            seen = Store.version
        }
    }

    @JavascriptInterface
    fun remove(k: String) {
        if (ok(k)) {
            Store.remove(k)
            seen = Store.version
        }
    }

    private fun ok(k: String) = k.startsWith("dafu-")
}
