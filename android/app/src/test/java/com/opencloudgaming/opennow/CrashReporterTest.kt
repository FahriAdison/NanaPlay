package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The force-close capture (see [CrashReporter]). These tests pin the guarantees the shared report
 * depends on, not the file plumbing: nothing credential-shaped may survive, the report stays bounded
 * and readable, and the frames a developer needs are the ones kept.
 */
class CrashReporterTest {

    private fun frame(className: String, method: String, file: String, line: Int) =
        StackTraceElement(className, method, file, line)

    /** A force close must still be reproduced in a report even when nothing else can. */
    @Test
    fun reportNamesExceptionAndAppFrames() {
        val report = buildCrashReport(
            error = IllegalStateException("CloudMatch response missing session")
                .apply {
                    stackTrace = arrayOf(
                        frame("com.opencloudgaming.opennow.GfnApiKt", "toSessionInfo", "GfnApi.kt", 2829),
                        frame("com.opencloudgaming.opennow.OpenNowViewModel", "play", "OpenNowViewModel.kt", 1946),
                    )
                },
            threadName = "main",
            versionName = "1.0.91",
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(report.contains("exception=java.lang.IllegalStateException"))
        assertTrue(report.contains("CloudMatch response missing session"))
        assertTrue(report.contains("com.opencloudgaming.opennow.GfnApiKt.toSessionInfo (GfnApi.kt:2829)"))
        assertTrue(report.contains("version=1.0.91"))
        assertTrue(report.contains("thread=main"))
    }

    /**
     * Tokens must not reach the file, whatever shape they arrive in.
     *
     * The fake token is assembled from pieces so this source never contains a literal token-shaped
     * value (the report and this file are both shared or reviewed).
     */
    @Test
    fun reportRedactsTokensAndIdentifiers() {
        val jwtPrefix = "eyJ"
        val jwtPayload = "eyJzd" + "WIiOiIxMjM0NTY3ODkwIn0"
        val jwt = "$jwtPrefix" + "hbGciOiJSUzI1NiJ9" + ".$jwtPayload" + ".abcdefghijklmnop"
        val message = "request failed Bearer $jwt device=550e8400-e29b-41d4-a716-446655440000 " +
            "token=abcdefghijklmnopqrstuvwxyzABCDEF&user=42"
        val report = buildCrashReport(RuntimeException(message), nowMs = 1_700_000_000_000L)
        // Assert on pieces, never on a whole token-shaped literal.
        assertFalse("JWT header must not survive", report.contains("hbGciOiJSUzI1NiJ9"))
        assertFalse("JWT payload must not survive", report.contains(jwtPayload))
        assertFalse("session UUID must not survive", report.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse("opaque token value must not survive", report.contains("abcdefghijklmnopqrstuvwxyzABCDEF"))
        assertTrue("label alone is harmless, value is redacted", report.contains("redacted"))
    }

    /** Long opaque ids are the other way credentials travel; they must not survive either. */
    @Test
    fun reportRedactsLongOpaqueStrings() {
        val report = sanitizeCrashText("session=abcdefghijklmnopqrstuvwxyz012345 sessionId=0123456789abcdef0123456789abcdef")
        assertFalse(report.contains("abcdefghijklmnopqrstuvwxyz012345"))
        assertFalse(report.contains("0123456789abcdef0123456789abcdef"))
    }

    /** A long stack trace must be bounded, or the report itself becomes unusable. */
    @Test
    fun reportBoundsItsSize() {
        val many = Array(400) { frame("com.opencloudgaming.opennow.SomeVeryLongClassName$1", "method", "File.kt", it) }
        val report = buildCrashReport(RuntimeException("x").apply { stackTrace = many })
        assertTrue("frames must be capped", report.contains("more frames"))
        assertTrue("report must stay small", report.length <= 16_400)
    }

    /** Cause chains are the part that says *why* the fault happened, so they survive. */
    @Test
    fun reportKeepsCauseChain() {
        val root = IllegalStateException("HTTP 503")
        val wrapped = RuntimeException("launch failed", root)
        val report = buildCrashReport(wrapped, nowMs = 1_700_000_000_000L)
        assertTrue(report.contains("causedBy[1]=java.lang.IllegalStateException: HTTP 503"))
        assertTrue(report.contains("exception=java.lang.RuntimeException: launch failed"))
    }

    /** A blank or unusable file must read back as null, not as garbage in the paste. */
    @Test
    fun reportIsUsableAfterSanitising() {
        val sanitized = sanitizeCrashText(buildCrashReport(RuntimeException("ok")))
        assertTrue(sanitized.startsWith("NanaPlay crash report"))
        assertFalse(sanitized.contains("null"))
    }
}
