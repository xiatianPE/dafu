package com.dafu.estimator

/**
 * Small process-local event channel for native services -> the active WebView bridge.
 * Payloads must be JSON objects; Bridge validates and dispatches them on the UI thread.
 */
object NativeEventBus {
    @Volatile private var listener: ((String, String) -> Unit)? = null

    fun register(callback: (String, String) -> Unit) {
        listener = callback
    }

    fun emit(name: String, json: String = "{}") {
        listener?.invoke(name, json)
    }
}
