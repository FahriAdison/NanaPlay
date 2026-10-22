package com.opencloudgaming.opennow

internal fun queueLaunchStatusText(state: OpenNowUiState): String {
    val session = state.streamSession
    val queuePosition = queueDisplayPosition(state)
    return when {
        queuePosition != null -> queuePositionText(state, queuePosition)
        session?.seatSetupStep == 1 -> "Waiting for a rig"
        state.launchPhase.equals("Connecting stream", ignoreCase = true) -> "Connecting stream"
        state.launchPhase.equals("Resuming session", ignoreCase = true) -> "Resuming session"
        state.launchPhase.equals("Setting up rig", ignoreCase = true) -> "Setting up rig"
        else -> "Starting session"
    }
}

/** Server request status for a queue that the provider abandoned. */
internal const val QUEUE_STATUS_ABANDONED = 69
/** Server request status for a session id the provider no longer recognises. */
internal const val QUEUE_STATUS_INVALID_SESSION = 22
/** A single 22 can be a race; this many in a row means the session is truly gone. */
internal const val QUEUE_INVALID_SESSION_MAX_CONSECUTIVE_POLLS = 3
/** Warn (never block) when the selected server pings worse than this. */
internal const val QUEUE_HIGH_PING_WARNING_MS = 150L

internal fun isQueueAbandonedStatus(statusCode: Int?): Boolean =
    statusCode == QUEUE_STATUS_ABANDONED

internal fun isQueueInvalidSessionStatus(statusCode: Int?): Boolean =
    statusCode == QUEUE_STATUS_INVALID_SESSION

internal fun shouldWarnHighPing(pingMs: Long?): Boolean =
    pingMs != null && pingMs > QUEUE_HIGH_PING_WARNING_MS

/**
 * Formats the live queue position, bilingual EN/ID.
 *
 * GFN's `seatSetupEta` is a fixed placeholder (30000 ms) that never counts
 * down, so it is intentionally not rendered. Only the position is shown.
 */
internal fun queuePositionText(state: OpenNowUiState, queuePosition: Int): String {
    val indonesian = state.settings.appLanguage == "in"
    return if (indonesian) "Antrian #$queuePosition"
    else "Queue #$queuePosition"
}

internal fun queueDisplayPosition(state: OpenNowUiState): Int? {
    val session = state.streamSession
    if (session?.seatSetupStep == 5) return null
    return state.queuePosition?.takeIf { it > 0 } ?: queueDisplayPosition(session)
}

internal fun queueDisplayPosition(session: SessionInfo?): Int? {
    if (session?.seatSetupStep == 5) return null
    return session?.queuePosition?.takeIf { it > 0 }
}

internal fun shouldShowQueueLaunchStatus(state: OpenNowUiState): Boolean {
    if (state.streamStatus == "idle") return false
    val sessionStatus = state.streamSession?.status
    return sessionStatus == null || sessionStatus !in setOf(2, 3)
}
