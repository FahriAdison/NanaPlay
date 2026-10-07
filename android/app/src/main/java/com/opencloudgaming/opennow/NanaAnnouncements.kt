package com.opencloudgaming.opennow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * NanaPlay 1.0.34 — GitHub-backed announcements.
 *
 * Papah publishes announcements by editing announcements.json at the repo
 * root (https://github.com/FahriAdison/NanaPlay). On startup the app fetches
 * the raw file, shows each unread, unexpired announcement as a dialog, and
 * remembers dismissed IDs in AppSettings so they never reappear.
 *
 * No server, no push permission, no auth — just a static JSON file.
 * Every call is fallible: offline/error silently skips, never blocks startup.
 */
data class NanaAnnouncement(
    val id: String,
    val title: String,
    val message: String,
)

object NanaAnnouncements {

    internal const val SOURCE_URL =
        "https://raw.githubusercontent.com/FahriAdison/NanaPlay/main/announcements.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Fetch announcements, keeping only ones that are unread and unexpired.
     * Returns empty list on any failure — never throws.
     */
    suspend fun fetchActive(dismissedIds: Set<String>): List<NanaAnnouncement> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(SOURCE_URL)
                    .header("User-Agent", "NanaPlay-Announcements/1.0")
                    .header("Accept", "application/json")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList<NanaAnnouncement>()
                    val body = response.body.string()
                    parse(body, dismissedIds)
                }
            }.getOrDefault(emptyList())
        }

    private fun parse(body: String, dismissedIds: Set<String>): List<NanaAnnouncement> {
        val out = ArrayList<NanaAnnouncement>()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return out
        val items = root.optJSONArray("announcements") ?: return out
        val now = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (id in dismissedIds) continue
            val showUntil = obj.optString("showUntil").takeIf { it.isNotBlank() }
            if (showUntil != null && isExpired(showUntil, now)) continue
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
            val message = obj.optString("message").takeIf { it.isNotBlank() } ?: continue
            out.add(NanaAnnouncement(id = id, title = title, message = message))
        }
        return out
    }

    /** Unparseable showUntil = no expiry (show it). */
    private fun isExpired(showUntil: String, now: Long): Boolean =
        runCatching { isoFormat.parse(showUntil)?.time?.let { it <= now } ?: false }
            .getOrDefault(false)
}
