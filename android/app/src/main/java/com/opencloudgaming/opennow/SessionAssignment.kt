package com.opencloudgaming.opennow

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val STANDARD_SESSION_CONTROL_HOST = Regex("^np-[a-z0-9]+(?:-[a-z0-9]+)+\\.(?:cloudmatchbeta|cloudmatch)\\.nvidiagrid\\.net$")

/** Only standard CloudMatch zone hosts on its HTTPS port may become session poll targets. */
internal fun standardCloudMatchSessionControlBaseUrl(rawHost: String?, port: Int?): String? {
    if (port != null && port != 443) return null
    val host = rawHost?.trim()?.trimEnd('.')?.lowercase(Locale.US) ?: return null
    return host.takeIf(STANDARD_SESSION_CONTROL_HOST::matches)?.let { "https://$it" }
}

/** Use the control host returned by this session snapshot for the next queue poll. */
internal fun SessionInfo.sessionControlPollBaseUrl(): String? {
    val launchHost = streamingBaseUrl?.toHttpUrlOrNull()?.host ?: return null
    val launchBaseOk = launchHost == "cloudmatchbeta.nvidiagrid.net" ||
        launchHost == "cloudmatch.nvidiagrid.net" ||
        launchHost.endsWith(".cloudmatchbeta.nvidiagrid.net") ||
        launchHost.endsWith(".cloudmatch.nvidiagrid.net")
    if (!launchBaseOk) return null
    val controlBase = sessionControlBaseUrl ?: return null
    val controlUrl = controlBase.toHttpUrlOrNull() ?: return null
    if (controlUrl.scheme != "https" || controlUrl.port != 443 || !STANDARD_SESSION_CONTROL_HOST.matches(controlUrl.host)) return null
    return "https://${controlUrl.host}"
}
