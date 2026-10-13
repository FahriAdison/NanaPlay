package com.opencloudgaming.opennow

private val CloudMatchStatus81Regex = Regex("""\bCloudMatch returned status\s+81\b""", RegexOption.IGNORE_CASE)
private val LaunchErrorWhitespaceRegex = Regex("""\s+""")
/** Word-boundary match so "dispatched"/"dispatch" never trigger the patch message. */
private val PatchWordRegex = Regex("""\bpatch(?:ing|ed|es)?\b""", RegexOption.IGNORE_CASE)
private val MaintenanceWordRegex = Regex("""\bmaintenance\b""", RegexOption.IGNORE_CASE)

internal fun normalizeLaunchErrorMessage(error: Throwable, gameTitle: String? = null): String {
    val text = error.message ?: return "Launch failed"
    return when {
        isFreeTierEntitlementError(text) ->
            "Your GeForce NOW account is on the Free tier. This game requires a Priority or Ultimate membership."
        isLimitedModeStreamingError(text) -> limitedModeStreamingMessage(gameTitle)
        isServerBusyError(text) ->
            "NVIDIA's servers are busy right now. Please try again in a few minutes."
        isNoNetworkError(text) ->
            "No network connection. Check your internet and try again."
        isSessionExpiredError(text) ->
            "Your session expired. Please sign in again."
        isRateLimitError(text) ->
            "Too many requests. Please wait a moment and try again."
        PatchWordRegex.containsMatchIn(text) || MaintenanceWordRegex.containsMatchIn(text) ->
            "Game is patching or under maintenance. Try again when NVIDIA finishes updating it."
        else -> text
    }
}

private fun isFreeTierEntitlementError(text: String): Boolean =
    text.contains("ENTITLEMENT_FAILURE_STATUS", ignoreCase = true) ||
        text.contains("8A910006", ignoreCase = true)

private fun isLimitedModeStreamingError(text: String): Boolean =
    text.contains("STREAMING_NOT_ALLOWED_IN_LIMITED_MODE", ignoreCase = true) ||
        text.contains("8A91000D", ignoreCase = true) ||
        (CloudMatchStatus81Regex.containsMatchIn(text) && text.contains("limited", ignoreCase = true))

/** CloudMatch status 81 without "limited" = server busy (transient), not an entitlement problem. */
private fun isServerBusyError(text: String): Boolean =
    CloudMatchStatus81Regex.containsMatchIn(text) && !text.contains("limited", ignoreCase = true)

private fun isNoNetworkError(text: String): Boolean =
    text.contains("Unable to resolve host", ignoreCase = true) ||
        text.contains("No address associated with hostname", ignoreCase = true) ||
        text.contains("Network is unreachable", ignoreCase = true) ||
        text.contains("SocketTimeoutException", ignoreCase = true) ||
        text.contains("ConnectTimeoutException", ignoreCase = true) ||
        text.contains("UnknownHostException", ignoreCase = true) ||
        text.contains("timed out", ignoreCase = true)

private fun isSessionExpiredError(text: String): Boolean =
    text.contains("invalid_grant", ignoreCase = true) ||
        text.contains("unauthorized", ignoreCase = true) ||
        text.contains("session expired", ignoreCase = true) ||
        text.contains("log in again", ignoreCase = true) ||
        text.contains("sign in again", ignoreCase = true) ||
        text.contains("failed (401", ignoreCase = true)

private fun isRateLimitError(text: String): Boolean =
    text.contains("rate limit", ignoreCase = true) ||
        text.contains("rate-limit", ignoreCase = true) ||
        text.contains("too many requests", ignoreCase = true) ||
        text.contains("failed (429", ignoreCase = true)

private fun limitedModeStreamingMessage(gameTitle: String?): String {
    val title = gameTitle
        ?.replace(LaunchErrorWhitespaceRegex, " ")
        ?.trim()
        .orEmpty()
    return if (title.isNotBlank()) {
        "$title is only available for Priority or Ultimate members"
    } else {
        "This game is only available for Priority or Ultimate members"
    }
}
