package com.opencloudgaming.opennow

import com.papahchan.nanaplay.BuildConfig
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// File-level so the pure builders below share the same bounds as the object's file I/O.
private const val CRASH_FILE_NAME = "last-crash.txt"
private const val MAX_REPORT_BYTES = 16_384
private const val MAX_MESSAGE_CHARS = 240
private const val MAX_FRAMES = 40
private const val MAX_CAUSES = 4
private const val TAG = "NanaPlayCrash"

/**
 * Last-resort crash capture for force closes.
 *
 * Why this exists: NanaPlay had no uncaught-exception handler, so a force close ended the process
 * with nothing on disk. The in-app diagnostics buffer is memory-only and dies with the process,
 * which is exactly why a "Share diagnostics" taken after a restart contained no trace at all.
 * This handler writes a *sanitized* report to internal storage before the process ends and lets
 * the normal chained handler finish the job.
 *
 * Sanitizing is the point, not an afterthought: the report must never contain tokens, credentials
 * or session identifiers. So [buildCrashReport] keeps only the exception type, layers of causes,
 * and stack frames, and [sanitizeCrashText] redacts every token-shaped and identifier-shaped value
 * before anything is persisted. See [CrashReporter.lastReport] for what gets shared.
 */
internal object CrashReporter {

    @Volatile
    private var installed = false

    private val lock = Any()

    /**
     * Installs the handler once, chaining to whatever handler was already set so the platform's own
     * crash reporting (and any test runner) still runs afterwards.
     */
    fun install(context: Context) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                writeReport(context, error, thread)
                previous?.uncaughtException(thread, error)
            }
            installed = true
        }
    }

    /** The sanitized report from the previous crash, or null when there was none. */
    fun lastReport(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) null else sanitizeCrashText(text.take(MAX_REPORT_BYTES))
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    private fun writeReport(context: Context, error: Throwable, thread: Thread) {
        runCatching {
            val report = buildCrashReport(
                error = error,
                threadName = thread.name,
                versionName = BuildConfig.VERSION_NAME,
            )
            crashFile(context).writeText(report)
            Log.e(TAG, report)
        }
        // Never let reporting itself turn into a secondary crash: everything above is best-effort,
        // and the chained handler below still performs the real process teardown.
    }
}

/**
 * Builds the crash report string. Pure so the redaction and bounding rules are unit-testable.
 *
 * `versionName` is public app metadata, not a credential; it is what makes two reports from
 * different builds distinguishable. The device is deliberately NOT identified here.
 */
internal fun buildCrashReport(
    error: Throwable,
    threadName: String? = null,
    versionName: String? = null,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val raw = StringWriter().use { writer ->
        PrintWriter(writer).use { print ->
            appendLine(print, "NanaPlay crash report")
            appendLine(print, "time=${nowMs}")
            appendLine(print, "thread=${threadName ?: "unknown"}")
            appendLine(print, "version=${versionName ?: "unknown"}")
            appendLine(print, "")
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < MAX_CAUSES) {
                val label = if (depth == 0) "exception" else "causedBy[$depth]"
                appendLine(print, "$label=${cause.javaClass.name}: ${truncate(cause.message ?: "")}")
                appendStackTrace(print, cause)
                appendLine(print, "")
                cause = cause.cause
                depth += 1
            }
        }
        writer.toString()
    }
    return sanitizeCrashText(raw)
}

private fun appendStackTrace(print: PrintWriter, throwable: Throwable) {
    val frames = throwable.stackTrace ?: return
    val shown = frames.take(MAX_FRAMES)
    for (frame in shown) {
        // Only the class, method and line are kept. A frame never carries a token or a session id,
        // and this is what actually locates the fault.
        appendLine(print, "    at ${frame.className}.${frame.methodName} (${frame.fileName ?: "unknown"}:${frame.lineNumber})")
    }
    val remaining = frames.size - shown.size
    if (remaining > 0) appendLine(print, "    ... $remaining more frames")
}

private fun truncate(value: String): String =
    if (value.length <= MAX_MESSAGE_CHARS) value else value.take(MAX_MESSAGE_CHARS) + "…[truncated]"

private fun appendLine(print: PrintWriter, line: String) {
    print.println(line)
}

/**
 * Redacts anything token- or identifier-shaped, so a stack frame or exception message can never
 * persist a credential. Deliberately over-broad: a false positive costs a little diagnostic detail,
 * a false negative leaks a secret.
 */
internal fun sanitizeCrashText(raw: String): String {
    var text = raw
    // JWT-shaped values (NVIDIA access tokens) and the two auth header spellings.
    text = text.replace(Regex("""eyJ[A-Za-z0-9._-]{8,}"""), "[redacted-token]")
    text = text.replace(Regex("""(?i)\b(?:Bearer|GFNJWT)\s+[A-Za-z0-9._~+/=-]{8,}"""), "[redacted-token]")
    // Long opaque tokens of any alphabet (client tokens, session ids, device ids).
    text = text.replace(Regex("""\b[A-Za-z0-9_-]{32,}\b"""), "[redacted-opaque]")
    // UUIDs in particular are session ids in this app.
    text = text.replace(
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b"""),
        "[redacted-id]",
    )
    // Query strings and fragments: where tokens and session ids actually travel.
    text = text.replace(Regex("""[?&#][A-Za-z0-9._~+/=-]+=[A-Za-z0-9._~+/=-]+"""), "[redacted-query]")
    // Secret-shaped key=value pairs anywhere, so a plain exception message cannot carry one either.
    // Over-broad on purpose: redacting "status=1" costs nothing, leaking a "token=..." does.
    text = text.replace(SECRET_KEY_VALUE_REGEX, "[redacted-secret]")
    return text
}

private val SECRET_KEY_VALUE_REGEX = Regex(
    """(?i)\b(?:token|access[_-]?token|refresh[_-]?token|client[_-]?token|secret|password|passwd|credential|authorization|session[_-]?id|client[_-]?id|device[_-]?id)\s*[=:]\s*[A-Za-z0-9._~+/=-]+""",
)
