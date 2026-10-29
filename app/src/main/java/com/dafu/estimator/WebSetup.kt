package com.dafu.estimator

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

object WebSetup {
    const val URL = "https://appassets.androidplatform.net/assets/dafu.html"
    private const val ORIGIN = "https://appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(web: WebView): Bridge {
        val ctx = web.context
        val bridge = Bridge()

        // 用 https://appassets.androidplatform.net 加载本地 assets，origin 固定，localStorage 才稳定
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            textZoom = 100
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.setBackgroundColor(Color.TRANSPARENT)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            // 只允许应用内页面，外部链接一律不打开
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                bridge.seen = Store.version
            }
        }

        web.addJavascriptInterface(bridge, "Native")

        // 页面脚本之前注入 shim，让 localStorage 读写同步到原生 Store；不支持时退回普通 localStorage
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shim = ctx.assets.open("native-shim.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(web, shim, setOf(ORIGIN))
        }
        return bridge
    }

    /** 原生 → 网页的扩展点：网页里定义 window.onNative(name, data) 即可接收 */
    fun emit(web: WebView, name: String, json: String) {
        web.evaluateJavascript(
            "window.onNative&&window.onNative(${JSONObject.quote(name)},$json)", null
        )
    }
}
