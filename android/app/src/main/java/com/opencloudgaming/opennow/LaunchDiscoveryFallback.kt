package com.opencloudgaming.opennow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/**
 * Why the pre-launch active-session lookup failed.
 *
 * Android asks the CloudMatch server for the account's active sessions on a single base URL (the
 * region the user picked), so a failing region can only ever fail that one lookup — never a scan of
 * every region. This enum still classifies the failure the same way the upstream OpenNOW shell
 * classifies `session_discovery_failed`, because "the lookup did not answer" is the only case where
 * a new launch may continue: everything else means we know, or strongly suspect, that an existing
 * seat owns the account.
 *
 * The provider is the final arbiter: `session.create` still fails with a conflict response if a
 * session really is running, and that path is routed to resume/end handling. Classification decides
 * only whether Android is allowed to ask, never whether a second session is created.
 */
internal enum class ActiveSessionsLookupFailureKind {
    /** The lookup did not return an answer we can trust: HTTP 5xx from maintenance, unusable body. */
    DiscoveryFailed,

    /** HTTP 401: authentication is gone or expired. Never masked by continuing to create. */
    Unauthorized,

    /** HTTP 403: the account is not permitted to look sessions up. Never masked. */
    Forbidden,

    /** The caller cancelled the launch (stop, new game, screen closed). Must unwind, not retry. */
    Cancelled,

    /** A coroutine deadline expired (for example `withTimeout` around the launch). */
    Timeout,

    /** DNS, connect or socket timeouts: the device cannot reach the provider at all. */
    Network,

    /** Anything we cannot classify. Fails closed: no fallback. */
    Other,
}

/**
 * Only an incomplete discovery result may continue a fresh launch intent.
 *
 * Authentication, cancellation, timeouts and transport failures stay strict on purpose: they either
 * prove the user cannot launch, or prove we simply do not know enough to try again.
 */
internal fun ActiveSessionsLookupFailureKind.allowsNewLaunchFallback(): Boolean =
    this == ActiveSessionsLookupFailureKind.DiscoveryFailed

/**
 * Maps an [ActiveSessionsLookupFailureKind] onto the exception a failed lookup can throw.
 *
 * [kotlinx.coroutines.TimeoutCancellationException] is checked before [CancellationException]
 * because it is a [CancellationException] and must still be reported as a timeout to callers.
 */
internal fun classifyActiveSessionsLookupFailure(error: Throwable): ActiveSessionsLookupFailureKind =
    when {
        error is TimeoutCancellationException -> ActiveSessionsLookupFailureKind.Timeout
        error is CancellationException -> ActiveSessionsLookupFailureKind.Cancelled
        error is CloudMatchHttpException -> when (error.code) {
            401 -> ActiveSessionsLookupFailureKind.Unauthorized
            403 -> ActiveSessionsLookupFailureKind.Forbidden
            else -> ActiveSessionsLookupFailureKind.DiscoveryFailed
        }

        error is CloudMatchRequestStatusException -> when (error.statusCode) {
            401 -> ActiveSessionsLookupFailureKind.Unauthorized
            403 -> ActiveSessionsLookupFailureKind.Forbidden
            else -> ActiveSessionsLookupFailureKind.DiscoveryFailed
        }

        error is IOException -> ActiveSessionsLookupFailureKind.Network
        // `GfnSessionRepository` reports an unparsable 2xx body through check(), which is a
        // discovery failure and not proof that a session ended.
        error is IllegalStateException -> ActiveSessionsLookupFailureKind.DiscoveryFailed
        else -> ActiveSessionsLookupFailureKind.Other
    }

/**
 * The launch-intent guards of upstream OpenNOW PR #1334, expressed against NanaPlay's state.
 *
 * Every field defaults to `false`, so a new field or an unmapped state turns the guard off instead
 * of silently widening the fallback. Callers fill this from `OpenNowUiState` at the moment the
 * discovery failure arrives, not from captured values, so a launch that went stale while the
 * lookup was in flight fails closed.
 *
 * Mapping to the Qt guard it replaces, in order:
 *
 * [signedIn] and [accountUnchanged]                  `signedIn` and `pendingLaunchParams.authGeneration`
 * [selectedGameUnchanged]                            `pendingLaunchParams.selectionIdentity` and `.actionGeneration`
 * [noStoreChoicePending]                             `pendingLaunchParams.requestContextKey` (a new store choice invalidates the intent)
 * [noPendingConflictLaunch]                          **proxy** for `!catalogOwner.mutationBusy` — not an exact counterpart (see below)
 * [noActiveSession]                                  `!activeSession`
 * [noConflictDecision]                               `!conflictSession`
 * [noQueueAbandoned]                                 `!launchConflictDetected`
 *
 * [noPendingConflictLaunch] is a **proxy**, not the Qt guard itself. Qt's `!catalogOwner.mutationBusy`
 * holds a launch while a launch-scoped catalog mutation (favorites, ownership, store links) is still
 * in flight. NanaPlay has no direct counterpart: its catalog mutations run as their own jobs and never
 * synchronously gate a launch that is already running. The proxy asserts the narrower property this
 * fallback actually depends on — `pendingActiveSessionLaunch == null`, so no conflict-handling path is
 * pending — and the staleness Qt guards with mutation busy-ness is covered structurally by `launchJob`:
 * a launch superseded by a newer game has its `launchJob` cancelled, so the coroutine unwinds through
 * [kotlinx.coroutines.CancellationException] before this guard is ever consulted.
 *
 * The proxy is therefore looser than Qt's guard — it protects a pending conflict flow rather than a
 * catalog mutation — and that is enough here because no Android catalog mutation can block this launch
 * path synchronously: the launch coroutine already owns the session jobs, and a concurrent catalog
 * mutation cannot pause the code between the discovery lookup and `session.create`.
 *
 * Guards with no Android counterpart are not invented as fields; they are structurally impossible in
 * this path and documented in [allowsNewLaunchFallback]:
 *
 * - `conflictSessionNeedsRefresh`: NanaPlay owns no "known conflict that needs rediscovery" state.
 * - `onboardingReplaying`: NanaPlay has no onboarding replay flow to defer launches for.
 */
internal data class NewLaunchIntentGuardInput(
    val signedIn: Boolean = false,
    val accountUnchanged: Boolean = false,
    val selectedGameUnchanged: Boolean = false,
    val noStoreChoicePending: Boolean = false,
    val noPendingConflictLaunch: Boolean = false,
    val noActiveSession: Boolean = false,
    val noConflictDecision: Boolean = false,
    val noQueueAbandoned: Boolean = false,
)

/**
 * True only for a fresh new-launch intent that owns no seat and no known conflict.
 *
 * The three guards without an Android field are satisfied by construction inside
 * [OpenNowViewModel.play]: the fallback is evaluated while a `session.create` has never run for
 * this launch, `state.activeSessionDecision == null` (see [NewLaunchIntentGuardInput.noConflictDecision])
 * rules out a conflict needing rediscovery, and no onboarding replay exists. A launch that already
 * produced a session, a conflict decision, or a queue-abandoned state cannot reach here.
 */
internal fun NewLaunchIntentGuardInput.allowsNewLaunchFallback(): Boolean =
    signedIn &&
        accountUnchanged &&
        selectedGameUnchanged &&
        noStoreChoicePending &&
        noPendingConflictLaunch &&
        noActiveSession &&
        noConflictDecision &&
        noQueueAbandoned
