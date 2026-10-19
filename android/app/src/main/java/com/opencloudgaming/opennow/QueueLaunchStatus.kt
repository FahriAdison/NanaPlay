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

/**
 * Formats "Queue position N" with the GFN seatSetupEta estimate when present,
 * bilingual EN/ID based on the app language setting. Eta is in milliseconds;
 * seconds are rounded up (30000 ms -> "est. 30s").
 */
internal fun queuePositionText(state: OpenNowUiState, queuePosition: Int): String {
    val indonesian = state.settings.appLanguage == "in"
    val etaSecs = state.streamSession?.seatSetupEta?.takeIf { it > 0 }?.let { (it + 999) / 1000 }
    val etaSuffix = when {
        etaSecs == null -> ""
        indonesian -> " · est. $etaSecs dtk"
        else -> " · est. ${etaSecs}s"
    }
    return if (indonesian) "Posisi antrian $queuePosition$etaSuffix"
    else "Queue position $queuePosition$etaSuffix"
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
