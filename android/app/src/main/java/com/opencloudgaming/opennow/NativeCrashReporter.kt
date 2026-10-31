package com.opencloudgaming.opennow

import android.content.Context
import com.papahchan.nanaplay.BuildConfig
import java.io.File

/**
 * Native (signal-based) crash capture.
 *
 * [CrashReporter] (Java [Thread.UncaughtExceptionHandler]) only fires for uncaught Java
 * exceptions. A force close that comes from a native signal (SIGSEGV/SIGABRT/SIGILL/SIGFPE/
 * SIGBUS) kills the process before any Java handler runs — which is the silent "back to Home,
 * no crash dialog" symptom seen in website client mode. This installs a POSIX signal handler in
 * the native library that writes a minimal report to internal storage, then chains to the
 * previously installed handler so the process still dies normally (platform tombstone preserved).
 *
 * The handler is best-effort and NOT strictly async-signal-safe (snprintf/backtrace are not
 * in the POSIX async-signal-safe list); correct process termination relies on chaining to the
 * old handler, not on any async-signal-safety claim.
 *
 * SIGKILL (e.g. OOM killer) cannot be captured by any handler — that case is out of scope.
 */
internal object NativeCrashReporter {

    @Volatile
    private var installed = false

    private val lock = Any()

    fun install(context: Context) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            runCatching {
                System.loadLibrary("opennow_native")
                val path = File(context.filesDir, "last-crash-native.txt").absolutePath
                installNativeCrashHandler(path, BuildConfig.VERSION_CODE)
                installed = true
            }
        }
    }

    /** The raw report written by the native handler on the previous crash, or null when there
     *  was none. Sanitization happens later, at share time, via sanitizeDiagnosticExport in
     *  OpenNowViewModel — the stored file is NOT sanitized. */
    fun lastReport(context: Context): String? {
        val file = File(context.filesDir, "last-crash-native.txt")
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) null else text.take(16_384)
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, "last-crash-native.txt").delete() }
    }

    private external fun installNativeCrashHandler(path: String, versionCode: Int)

    init {
        runCatching { System.loadLibrary("opennow_native") }
    }
}
