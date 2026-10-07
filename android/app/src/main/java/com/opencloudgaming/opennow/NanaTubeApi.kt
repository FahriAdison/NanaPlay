package com.opencloudgaming.opennow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NanaPlay 1.0.26 — minimal YouTube InnerTube client for online music.
 *
 * Approach inspired by Metrolist (GPL-3.0, MetrolistGroup/Metrolist) and its
 * InnerTune lineage: talk to YouTube Music's InnerTube endpoints directly,
 * search songs, then resolve a playable audio-only stream URL for ExoPlayer.
 *
 * Deliberately minimal — only two endpoints, no cipher/PoToken machinery:
 * the ANDROID player client currently returns plain (non-encrypted) audio
 * URLs. If YouTube changes its API, [NanaTubeException] is thrown and the UI
 * shows "Online music temporarily unavailable" instead of crashing.
 *
 * NOTE: InnerTube is unofficial and reverse-engineered. It can break whenever
 * YouTube changes things; treat every call as fallible.
 */
data class NanaOnlineTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String?,
)

class NanaTubeException(message: String, cause: Throwable? = null) : Exception(message, cause)

object NanaTubeApi {

    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val SEARCH_URL =
        "https://music.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false"
    private const val PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Search YouTube Music for songs matching [query]. Returns song-like
     * results (title, artist, duration, thumbnail, videoId).
     */
    suspend fun searchSongs(query: String): List<NanaOnlineTrack> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20240101.01.00")
                .put("hl", "en")
                .put("gl", "US")))
            .put("query", query)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = post(SEARCH_URL, body)
        parseSearchResults(JSONObject(response))
    }

    /**
     * Resolve a direct audio-only stream URL for [videoId], picking the
     * highest-bitrate audio format. Returns null when unavailable.
     */
    suspend fun resolveAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "20.10.35")
                .put("androidSdkVersion", 34)
                .put("hl", "en")
                .put("gl", "US")))
            .put("videoId", videoId)
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val json = JSONObject(post(PLAYER_URL, body))
        if (json.optJSONObject("playabilityStatus")?.optString("status") != "OK") {
            return@withContext null
        }
        val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: return@withContext null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (!f.optString("mimeType").startsWith("audio/")) continue
            val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bitrate = f.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        bestUrl
    }

    private fun post(url: String, body: okhttp3.RequestBody): String {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw NanaTubeException("InnerTube HTTP ${resp.code}")
                }
                return resp.body?.string()
                    ?: throw NanaTubeException("InnerTube empty response")
            }
        } catch (e: NanaTubeException) {
            throw e
        } catch (e: Exception) {
            throw NanaTubeException("InnerTube request failed", e)
        }
    }

    // ---- search response parsing ----

    private fun parseSearchResults(root: JSONObject): List<NanaOnlineTrack> {
        val out = ArrayList<NanaOnlineTrack>()
        val sections = root
            .optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return out
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            // Top result card + regular sections (YouTube migrated search
            // sections to itemSectionRenderer; handle both shapes).
            val shelf = section.optJSONObject("musicCardShelfRenderer")
            val itemSection = section.optJSONObject("itemSectionRenderer")
            val contents: JSONArray? = when {
                shelf != null -> shelf.optJSONArray("contents")
                itemSection != null -> itemSection.optJSONArray("contents")
                else -> null
            }
            if (contents == null) continue
            for (j in 0 until contents.length()) {
                parseSongItem(contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer"))
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseSongItem(item: JSONObject?): NanaOnlineTrack? {
        item ?: return null
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            .takeIf { it?.isNotBlank() == true }
            ?: item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").takeIf { it?.isNotBlank() == true }
            ?: item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").takeIf { it?.isNotBlank() == true }
            ?: return null

        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        val title = flexColumnText(flexColumns, 0).takeIf { it.isNotBlank() } ?: return null
        // Subtitle looks like "Song • Artist • 3:54" — artist is the middle part.
        val subtitle = flexColumnText(flexColumns, 1)
        val artist = subtitle.split("•").map { it.trim() }
            .getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Unknown artist"
        val duration = item.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            .takeIf { it?.isNotBlank() == true }
            ?: subtitle.split("•").map { it.trim() }.lastOrNull()
                ?.takeIf { it.matches(Regex("\\d+:\\d+(:\\d+)?")) }
            ?: ""

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnailUrl = if (thumbnails != null && thumbnails.length() > 0) {
            thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return NanaOnlineTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            durationText = duration,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun flexColumnText(flexColumns: JSONArray, index: Int): String {
        val runs = flexColumns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }
}
