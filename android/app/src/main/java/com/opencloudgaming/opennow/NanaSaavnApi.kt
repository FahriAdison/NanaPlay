package com.opencloudgaming.opennow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * NanaPlay 1.0.31 — JioSaavn client for online music.
 *
 * Replaces the YouTube InnerTube backend (1.0.26–1.0.30), which proved
 * unreliable for playback (throttling, HTTP 403, "source unavailable").
 * JioSaavn serves free, no-auth, direct CDN stream URLs (aac.saavncdn.com)
 * that are not IP-bound and need no Range/UA hacks — ExoPlayer plays them
 * with a plain request.
 *
 * Uses the ShnwazDev public JioSaavn API wrapper. API docs/response shapes
 * follow the sumitkolhe/jiosaavn-api (MIT) project it is based on.
 * Unofficial — treat every call as fallible; the UI shows
 * "Online music temporarily unavailable" instead of crashing.
 */
data class NanaOnlineTrack(
    val saavnId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String?,
    val playCountText: String?,
)

class NanaSaavnException(message: String, cause: Throwable? = null) : Exception(message, cause)

object NanaSaavnApi {

    private const val BASE_URL = "https://shnwazdev-jiosaavn-apii.vercel.app"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Search JioSaavn for songs matching [query]. Returns up to [limit]
     * song results (id, title, artist, duration, thumbnail, play count).
     */
    suspend fun searchSongs(query: String, limit: Int = 20): List<NanaOnlineTrack> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = get("$BASE_URL/api/search/songs?query=$encoded&limit=$limit")
            parseSongList(toSongArray(body))
        }

    /**
     * Resolve the best-quality direct stream URL for a JioSaavn song [id].
     * Returns null when unavailable.
     */
    suspend fun resolveAudioUrl(id: String): String? = withContext(Dispatchers.IO) {
        val body = get("$BASE_URL/api/songs/$id")
        val songs = toSongArray(body)
        var bestUrl: String? = null
        var bestKbps = -1
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val downloads = song.optJSONArray("downloadUrl") ?: continue
            for (j in 0 until downloads.length()) {
                val entry = downloads.optJSONObject(j) ?: continue
                val url = entry.optString("url").takeIf { it.isNotBlank() } ?: continue
                // Quality looks like "320kbps" — parse the leading number.
                val kbps = entry.optString("quality").takeWhile { it.isDigit() }
                    .toIntOrNull() ?: 0
                if (kbps > bestKbps) {
                    bestKbps = kbps
                    bestUrl = url
                }
            }
        }
        bestUrl
    }

    // ---- response parsing ----

    /** Response formats seen in the wild:
     *  - bare JSON array: [...]
     *  - {"success":true,"data":{"results":[...]}}  (search endpoint, current)
     *  - {"data":[...]}  (song endpoint, older)
     */
    private fun toSongArray(body: String): JSONArray {
        val trimmed = body.trim()
        return if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONObject("data")?.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: JSONArray()
        }
    }

    private fun parseSongList(songs: JSONArray): List<NanaOnlineTrack> {
        val out = ArrayList<NanaOnlineTrack>()
        for (i in 0 until songs.length()) {
            parseSong(songs.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseSong(song: JSONObject?): NanaOnlineTrack? {
        song ?: return null
        val id = song.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = song.optString("name").takeIf { it.isNotBlank() } ?: return null
        val artist = song.optJSONObject("artists")
            ?.optJSONArray("primary")
            ?.optJSONObject(0)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown artist"
        val durationText = formatDuration(song.optInt("duration", 0))
        val thumbnailUrl = pickThumbnail(song.optJSONArray("image"))
        val playCountText = formatPlayCount(song.optLong("playCount", 0))
        return NanaOnlineTrack(
            saavnId = id,
            title = title,
            artist = artist,
            durationText = durationText,
            thumbnailUrl = thumbnailUrl,
            playCountText = playCountText,
        )
    }

    private fun pickThumbnail(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null
        // Prefer 150x150; fall back to the largest available.
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            if (img.optString("quality") == "150x150") {
                return img.optString("url").takeIf { it.isNotBlank() }
            }
        }
        return images.optJSONObject(images.length() - 1)
            ?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return ""
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun formatPlayCount(count: Long): String? {
        if (count <= 0) return null
        return when {
            count >= 1_000_000 -> "${count / 1_000_000}M plays"
            count >= 1_000 -> "${count / 1_000}K plays"
            else -> "$count plays"
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw NanaSaavnException("JioSaavn HTTP ${resp.code}")
                }
                return resp.body?.string()
                    ?: throw NanaSaavnException("JioSaavn empty response")
            }
        } catch (e: NanaSaavnException) {
            throw e
        } catch (e: Exception) {
            throw NanaSaavnException("JioSaavn request failed", e)
        }
    }
}
