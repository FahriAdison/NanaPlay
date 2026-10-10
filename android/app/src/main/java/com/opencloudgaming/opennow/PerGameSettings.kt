package com.opencloudgaming.opennow

/**
 * Per-game stream settings (NanaPlay).
 *
 * Global stream settings live in [AppSettings.stream]. Individual games can override them via
 * [AppSettings.perGameSettings], keyed by [GameInfo.id] — the same key already used for
 * favoriteGameIds and defaultGameVariantIds, so it stays stable across launches and content
 * refreshes.
 *
 * Only the whole [StreamSettings] is stored/applied; no other [AppSettings] fields are
 * duplicated here. The map persists automatically with the rest of the JSON settings store.
 */

/** Stream settings to use when launching [gameId]: its own saved override, or global. */
fun AppSettings.streamSettingsFor(gameId: String): StreamSettings =
    perGameSettings[gameId] ?: stream

/** True when [gameId] has a saved per-game override (shown as a badge in game details). */
fun AppSettings.hasPerGameSettings(gameId: String): Boolean =
    perGameSettings.containsKey(gameId)
