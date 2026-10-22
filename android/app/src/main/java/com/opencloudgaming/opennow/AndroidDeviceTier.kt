package com.opencloudgaming.opennow

import java.util.Locale

/**
 * Detects entry-level phone chipsets that should start on the light profile:
 * H264, at most 720p, and at most 12 Mbps.
 *
 * The fingerprints come from `Build.SOC_MODEL` / `Build.SOC_MANUFACTURER`
 * (API 31+) with `Build.HARDWARE`, board, device and model as fallbacks,
 * because many budget vendors leave SOC_MODEL blank or report only a board
 * name. Matching is case-insensitive and pattern-based rather than a fixed
 * device list, so new Helio G3x / Unisoc T6xx / Snapdragon 4xx models are
 * covered without an app update.
 */
private val ENTRY_LEVEL_CHIPSET_PATTERNS = listOf(
    // MediaTek Helio G25/G35/G36/G37 and the older entry Helio A/P lines.
    Regex("""helio\s+(?:g3\d|g25|a\d|p2\d|p3\d)"""),
    // Common MediaTek part numbers used by those Helios.
    Regex("""mt(?:6580|6761|6762|6765)"""),
    // Unisoc/Spreadtrum entry SoCs and their board prefixes.
    Regex("""unisoc\s+t[367]\d{2}"""),
    Regex("""\b(?:t606|t610|t612|t616|t618|t310|t700)\b"""),
    Regex("""sc(?:9832|9863)"""),
    Regex("""ums(?:312|512|9230)"""),
    // Snapdragon 4xx and the 4-series rebrands.
    Regex("""snapdragon\s+4(?:\d{2}|\s+gen\s+\d)"""),
    Regex("""\bsdm4\d{2}\b"""),
    Regex("""\bsm4\d{3}\b"""),
    // Older 400-series Qualcomm part numbers that report no marketing name.
    Regex("""msm(?:8917|8920|8937|8940)"""),
    // Samsung's budget SoC.
    Regex("""exynos\s+850"""),
)

internal fun isEntryLevelChipset(vararg identifiers: String?): Boolean {
    val fingerprint = identifiers
        .asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(" ")
        .lowercase(Locale.US)
    if (fingerprint.isBlank()) return false
    return ENTRY_LEVEL_CHIPSET_PATTERNS.any { pattern -> pattern.containsMatchIn(fingerprint) }
}

/** 720p-class profile applied automatically to entry-level chipsets. */
internal const val ENTRY_LEVEL_MAX_WIDTH = 1280
internal const val ENTRY_LEVEL_MAX_HEIGHT = 720
internal const val ENTRY_LEVEL_MAX_BITRATE_MBPS = 12
