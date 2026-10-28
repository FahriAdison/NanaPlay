package com.opencloudgaming.opennow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Port of upstream OpenNOW PR #1334's `tst_embeddedorchestration.cpp` `launchDiscoveryFailure`
 * data-driven table for NanaPlay.
 *
 * The same distinction is asserted: only a discovery failure on a clean, current new-launch intent may
 * continue to `session.create`; authentication, cancellation, timeout and transport failures, and
 * every dirty-intent variant, keep the launch strict.
 *
 * This table covers the two decisions NanaPlay can express in pure functions: how a failed lookup is
 * classified, and whether a launch intent is clean. The create-time conflict row of the Qt table
 * lives behind `OpenNowViewModel`'s repository calls and is not covered here (see the report).
 */
class LaunchDiscoveryFallbackTest {

    /**
     * `withTimeout` is the only way a test can obtain a real `TimeoutCancellationException`: its
     * constructors are internal to kotlinx.coroutines. This also exercises the ordering guarantee the
     * classifier relies on, because [kotlinx.coroutines.TimeoutCancellationException] *is* a
     * `CancellationException` and must still classify as a timeout, not as a cancellation.
     */
    private fun realTimeoutError(): Throwable =
        try {
            runBlocking { withTimeout(1) { delay(50) } }
            error("withTimeout did not expire")
        } catch (error: Throwable) {
            error
        }

    private fun cleanGuard() = NewLaunchIntentGuardInput(
        signedIn = true,
        accountUnchanged = true,
        selectedGameUnchanged = true,
        noStoreChoicePending = true,
        noPendingConflictLaunch = true,
        noActiveSession = true,
        noConflictDecision = true,
        noQueueAbandoned = true,
    )

    @Test
    fun onlyDiscoveryFailureContinuesCleanLaunch() {
        assertTrue(
            classifyActiveSessionsLookupFailure(
                CloudMatchHttpException(503, "Selected server unavailable (HTTP 503)."),
            ).allowsNewLaunchFallback(),
        )
        assertTrue(cleanGuard().allowsNewLaunchFallback())
    }

    /**
     * Authentication, cancellation, timeout and transport failures never create a session.
     *
     * A bare `IllegalStateException` is deliberately absent: every `error`/`check` inside
     * `getActiveSessions` reports an unusable provider answer, which is a discovery failure (see
     * [unusableBodyIsADiscoveryFailure]) rather than proof that launching is unsafe.
     */
    @Test
    fun strictFailureCodesFailClosedOnCleanContext() {
        val failures = listOf<Throwable>(
            CloudMatchHttpException(401, "Unauthorized (HTTP 401)."),
            CloudMatchHttpException(403, "Forbidden (HTTP 403)."),
            CancellationException("Stop requested"),
            realTimeoutError(),
            UnknownHostException("Unable to resolve host"),
            IOException("Socket closed"),
        )
        for (failure in failures) {
            val kind = classifyActiveSessionsLookupFailure(failure)
            assertFalse(
                "kind=$kind must not allow the fallback",
                kind.allowsNewLaunchFallback(),
            )
        }
    }

    /** A real coroutine timeout must classify as a timeout, not as a plain cancellation. */
    @Test
    fun realTimeoutIsClassifiedAsTimeoutBeforeCancellation() {
        assertEquals(
            ActiveSessionsLookupFailureKind.Timeout,
            classifyActiveSessionsLookupFailure(realTimeoutError()),
        )
        assertEquals(
            ActiveSessionsLookupFailureKind.Cancelled,
            classifyActiveSessionsLookupFailure(CancellationException("Stop requested")),
        )
    }

    /** Provider request-status codes map the same way HTTP codes do: 401/403 stay auth failures. */
    @Test
    fun providerStatusUnauthorizedIsNotADiscoveryFailure() {
        assertFalse(
            classifyActiveSessionsLookupFailure(
                CloudMatchRequestStatusException(401, "unauthorized", null),
            ).allowsNewLaunchFallback(),
        )
        assertFalse(
            classifyActiveSessionsLookupFailure(
                CloudMatchRequestStatusException(403, "forbidden", null),
            ).allowsNewLaunchFallback(),
        )
    }

    /** A non-1 provider status is the provider answering, which is a discovery failure. */
    @Test
    fun providerStatusNonOneIsADiscoveryFailure() {
        assertTrue(
            classifyActiveSessionsLookupFailure(
                CloudMatchRequestStatusException(500, "server busy", null),
            ).allowsNewLaunchFallback(),
        )
    }

    /** An unparsable 2xx body is the discovery request failing, not proof that a session ended. */
    @Test
    fun unusableBodyIsADiscoveryFailure() {
        assertTrue(
            classifyActiveSessionsLookupFailure(
                IllegalStateException("Active session lookup returned an invalid response."),
            ).allowsNewLaunchFallback(),
        )
    }

    /** Unknown exception types fail closed rather than widening the fallback. */
    @Test
    fun unknownFailuresFailClosed() {
        assertFalse(
            classifyActiveSessionsLookupFailure(OutOfMemoryError("unclassifiable"))
                .allowsNewLaunchFallback(),
        )
    }

    /**
     * The Qt dirty-context rows. Each one alone must keep the launch strict even when the failure is
     * a discovery failure, because a launch that went stale, lost its game, or already owns or knows
     * a session is not a new-launch intent any more.
     */
    @Test
    fun dirtyIntentKeepsDiscoveryFallbackClosed() {
        val discovery = CloudMatchHttpException(503, "Region unavailable (HTTP 503).")
        val dirty = listOf<Pair<String, () -> NewLaunchIntentGuardInput>>(
            "launchConflictDetected = true" to { cleanGuard().copy(noQueueAbandoned = false) },
            "conflictSession is known" to { cleanGuard().copy(noConflictDecision = false) },
            "conflictSessionNeedsRefresh" to { cleanGuard().copy(noConflictDecision = false) },
            "activeSession is known" to { cleanGuard().copy(noActiveSession = false) },
            "selected identity changed (stale launch)" to { cleanGuard().copy(selectedGameUnchanged = false) },
            "account changed" to { cleanGuard().copy(accountUnchanged = false) },
            "pending store choice is open" to { cleanGuard().copy(noStoreChoicePending = false) },
            "a pending conflict launch is running" to { cleanGuard().copy(noPendingConflictLaunch = false) },
            "signed out" to { cleanGuard().copy(signedIn = false) },
        )
        for ((label, guard) in dirty) {
            assertTrue(
                "kind must allow fallback for $label",
                classifyActiveSessionsLookupFailure(discovery).allowsNewLaunchFallback(),
            )
            assertFalse(
                "$label must not continue the launch",
                guard().allowsNewLaunchFallback(),
            )
        }
    }

    /** A guard that reports only one field must not pass: every guard is required. */
    @Test
    fun partialGuardFailsClosed() {
        assertFalse(NewLaunchIntentGuardInput(signedIn = true).allowsNewLaunchFallback())
        assertFalse(NewLaunchIntentGuardInput().allowsNewLaunchFallback())
    }
}
