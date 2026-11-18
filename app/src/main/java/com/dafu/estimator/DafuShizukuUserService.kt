package com.dafu.estimator

import android.os.Environment
import android.system.Os
import java.io.File

/**
 * Runs inside Shizuku's UserService process (ADB shell UID when started wirelessly).
 * The screenshot is written to this app's external files directory so the normal
 * app process can decode it without transferring a large PNG through Binder.
 */
class DafuShizukuUserService : IDafuShizukuService.Stub() {
    override fun getShellUid(): Int = Os.getuid()

    override fun captureScreen(): String {
        return try {
            val base = File(
                Environment.getExternalStorageDirectory(),
                "Android/data/com.dafu.estimator/files"
            )
            if (!base.exists() && !base.mkdirs()) {
                return "ERROR: cannot create app external files directory"
            }
            val output = File(base, "dafu_shizuku_capture.png")
            if (output.exists()) output.delete()

            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/screencap", "-p", output.absolutePath)
            )
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                "ERROR: screencap exit=$exitCode $error"
            } else if (!output.exists() || output.length() <= 0L) {
                "ERROR: screencap produced an empty file"
            } else {
                "OK:${output.absolutePath}:${output.length()}"
            }
        } catch (e: Exception) {
            "ERROR:${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }
}
