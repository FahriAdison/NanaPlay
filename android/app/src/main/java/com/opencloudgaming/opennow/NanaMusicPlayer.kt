package com.opencloudgaming.opennow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * NanaPlay 1.0.25 — local music player ("play your own music from storage").
 * 1.0.26 — online tracks from YouTube InnerTube can be added to the same
 * queue; they resolve to a fresh stream URL at play time (URLs expire).
 *
 * Plays audio files from device storage both outside and inside a stream. Uses
 * ExoPlayer (Media3, already a dependency) with audio-focus handling DISABLED so
 * music mixes with the game audio instead of fighting it — exactly what the
 * feature request asks for ("musik dan suara game boleh mix").
 *
 * No bitrate/size limits: files are local, decoded on-device.
 */
data class NanaTrack(
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val isOnline: Boolean = false,
    /** 1.0.26: videoId for online tracks — used to re-resolve the stream URL on restore (URLs expire). */
    val videoId: String? = null,
)

object NanaMusicPlayer {

    private var player: androidx.media3.exoplayer.ExoPlayer? = null

    private val _tracks = MutableStateFlow<List<NanaTrack>>(emptyList())
    val tracks: StateFlow<List<NanaTrack>> = _tracks.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val currentTrack: NanaTrack?
        get() {
            val idx = _currentIndex.value
            return _tracks.value.getOrNull(idx)
        }

    // ---- 1.0.26: playback state persistence (Spotify-style restore) ----
    //
    // Persists the playlist (local URIs + online videoIds/metadata), the
    // current track identity, and the playback position. Restored lazily on
    // first player access after a process restart; the user continues paused
    // at the exact track and second where they left off.
    private const val MUSIC_PREFS = "nana_music_state"
    private const val KEY_STATE_JSON = "state_json"
    private const val SAVE_INTERVAL_MS = 5000L

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var appContextRef: Context? = null
    private var restored = false

    /** Launch a one-time async restore on first player access per process. */
    private fun ensureRestored(context: Context) {
        if (restored) return
        restored = true
        val appCtx = context.applicationContext
        persistScope.launch {
            runCatching { restoreState(appCtx) }
        }
    }

    private fun startPeriodicSave() {
        saveJob?.cancel()
        saveJob = persistScope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                appContextRef?.let { saveState(it) }
            }
        }
    }

    private fun stopPeriodicSave() {
        saveJob?.cancel()
        saveJob = null
    }

    /** Best-effort save of playlist + position. Never throws. */
    private fun saveState(context: Context) {
        runCatching {
            val exo = player ?: return
            val tracks = _tracks.value
            val arr = JSONArray()
            tracks.forEach { t ->
                val o = JSONObject()
                    .put("title", t.title)
                    .put("isOnline", t.isOnline)
                if (t.artist != null) o.put("artist", t.artist)
                if (t.thumbnailUrl != null) o.put("thumbnailUrl", t.thumbnailUrl)
                if (t.isOnline) {
                    o.put("videoId", t.videoId)
                } else {
                    o.put("uri", t.uri.toString())
                }
                arr.put(o)
            }
            val current = tracks.getOrNull(_currentIndex.value)
            val state = JSONObject()
                .put("tracks", arr)
                .put("positionMs", exo.currentPosition)
                .put("currentUri", if (current?.isOnline == false) current.uri.toString() else null)
                .put("currentVideoId", if (current?.isOnline == true) current.videoId else null)
            context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE_JSON, state.toString())
                .apply()
        }
    }

    /**
     * Rebuild the queue from the last saved state. Local tracks are re-added
     * only when we still hold a persisted URI permission; online tracks get a
     * freshly resolved stream URL (old URLs expire). The current track is found
     * by identity so skipped tracks can't shift it. Restores paused — the user
     * taps play to continue from the saved position.
     */
    private suspend fun restoreState(context: Context) {
        val jsonStr = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE_JSON, null) ?: return
        val state = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return
        val arr = state.optJSONArray("tracks") ?: return
        if (arr.length() == 0) return

        val rebuilt = ArrayList<NanaTrack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("isOnline", false)) {
                val videoId = o.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                val url = try {
                    NanaTubeApi.resolveAudioUrl(videoId)
                } catch (e: Exception) {
                    null
                } ?: continue // offline or YouTube changed — skip, don't break restore
                rebuilt.add(
                    NanaTrack(
                        uri = Uri.parse(url),
                        title = o.optString("title", "Unknown"),
                        artist = o.optString("artist").takeIf { it.isNotBlank() },
                        thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        isOnline = true,
                        videoId = videoId,
                    ),
                )
            } else {
                val uriStr = o.optString("uri").takeIf { it.isNotBlank() } ?: continue
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "content" && !hasPersistedReadPermission(context, uri)) continue
                rebuilt.add(
                    NanaTrack(
                        uri = uri,
                        title = o.optString("title", "Unknown"),
                    ),
                )
            }
        }
        if (rebuilt.isEmpty()) return

        val exo = ensurePlayer(context)
        val positionMs = state.optLong("positionMs", 0L)
        val currentUri = state.optString("currentUri").takeIf { it.isNotBlank() }
        val currentVideoId = state.optString("currentVideoId").takeIf { it.isNotBlank() }
        synchronized(this) {
            // Don't clobber a playlist the user already built this session
            // (e.g. they added tracks while restore was in flight).
            if (_tracks.value.isNotEmpty()) return
            _tracks.value = rebuilt
            rebuilt.forEach { exo.addMediaItem(MediaItem.fromUri(it.uri)) }
            exo.prepare()
            val idx = when {
                currentVideoId != null ->
                    rebuilt.indexOfFirst { it.isOnline && it.videoId == currentVideoId }
                currentUri != null ->
                    rebuilt.indexOfFirst { !it.isOnline && it.uri.toString() == currentUri }
                else -> 0
            }.coerceAtLeast(0)
            _currentIndex.value = idx
            exo.seekTo(idx, positionMs.coerceAtLeast(0L))
            exo.pause()
        }
    }

    private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        }.getOrDefault(false)
    }

    @Synchronized
    private fun ensurePlayer(context: Context): androidx.media3.exoplayer.ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
        appContextRef = appContext
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = androidx.media3.exoplayer.ExoPlayer.Builder(appContext)
            // Do NOT request audio focus: music must mix with the stream's game
            // audio, not pause/duck it (or be paused by it).
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startPeriodicSave()
                    } else {
                        stopPeriodicSave()
                        // Persist position on every pause — cheap and exact.
                        persistScope.launch { saveState(appContext) }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentIndex.value = exo.currentMediaItemIndex
                    // Track change: persist immediately so a kill keeps the right song.
                    persistScope.launch { saveState(appContext) }
                }
            },
        )
        player = exo
        return exo
    }

    @Synchronized
    fun addTracks(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        ensureRestored(context)
        val appContext = context.applicationContext
        val newTracks = uris.map { uri ->
            NanaTrack(uri = uri, title = resolveTitle(appContext, uri))
        }
        val exo = ensurePlayer(appContext)
        val startIndex = _tracks.value.size
        _tracks.value = _tracks.value + newTracks
        newTracks.forEach { track ->
            exo.addMediaItem(MediaItem.fromUri(track.uri))
        }
        exo.prepare()
        // Auto-start playing the first newly added track when nothing was playing.
        if (!_isPlaying.value && _currentIndex.value < 0) {
            _currentIndex.value = startIndex
            exo.seekTo(startIndex, 0)
            exo.play()
        }
    }

    @Synchronized
    fun removeTrack(context: Context, index: Int) {
        val tracks = _tracks.value
        if (index !in tracks.indices) return
        val exo = player ?: return
        val wasCurrent = index == exo.currentMediaItemIndex
        exo.removeMediaItem(index)
        _tracks.value = tracks.toMutableList().also { it.removeAt(index) }
        if (_tracks.value.isEmpty()) {
            exo.stop()
            exo.clearMediaItems()
            _currentIndex.value = -1
        } else if (wasCurrent) {
            _currentIndex.value = exo.currentMediaItemIndex.coerceIn(0, _tracks.value.size - 1)
        }
        // Keep the persisted playlist in sync after deletions.
        persistScope.launch { appContextRef?.let { saveState(it) } }
    }

    @Synchronized
    fun play(context: Context, index: Int) {
        val tracks = _tracks.value
        if (index !in tracks.indices) return
        ensureRestored(context)
        val exo = ensurePlayer(context.applicationContext)
        _currentIndex.value = index
        exo.seekTo(index, 0)
        exo.play()
    }

    /**
     * 1.0.26 — resolve [online] to a fresh stream URL and play it.
     * Returns false when the stream URL cannot be resolved (caller shows
     * "Online music temporarily unavailable"). Must be called from a coroutine;
     * the resolve itself runs on Dispatchers.IO inside NanaTubeApi.
     */
    suspend fun playOnlineTrack(context: Context, online: NanaOnlineTrack): Boolean {
        val url = try {
            NanaTubeApi.resolveAudioUrl(online.videoId)
        } catch (e: Exception) {
            null
        } ?: return false
        val track = NanaTrack(
            uri = Uri.parse(url),
            title = online.title,
            artist = online.artist,
            thumbnailUrl = online.thumbnailUrl,
            isOnline = true,
            videoId = online.videoId,
        )
        ensureRestored(context)
        val appContext = context.applicationContext
        val exo = ensurePlayer(appContext)
        val index: Int
        synchronized(this) {
            // Reuse an existing queue entry for the same video when present.
            val existing = _tracks.value.indexOfFirst {
                it.isOnline && it.title == track.title && it.artist == track.artist
            }
            index = if (existing >= 0) {
                existing
            } else {
                _tracks.value = _tracks.value + track
                exo.addMediaItem(MediaItem.fromUri(track.uri))
                exo.prepare()
                _tracks.value.size - 1
            }
            _currentIndex.value = index
        }
        exo.seekTo(index, 0)
        exo.play()
        return true
    }

    @Synchronized
    fun togglePlayPause(context: Context) {
        ensureRestored(context)
        val exo = ensurePlayer(context.applicationContext)
        if (_tracks.value.isEmpty()) return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            // If playback never started, start from the current/first track.
            if (exo.currentMediaItem == null && _tracks.value.isNotEmpty()) {
                val idx = _currentIndex.value.coerceIn(0, _tracks.value.size - 1).coerceAtLeast(0)
                _currentIndex.value = idx
                exo.seekTo(idx, 0)
            }
            exo.play()
        }
    }

    @Synchronized
    fun next(context: Context) {
        val tracks = _tracks.value
        if (tracks.isEmpty()) return
        ensureRestored(context)
        val exo = ensurePlayer(context.applicationContext)
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
        } else {
            // Wrap around to the first track.
            play(context.applicationContext, 0)
        }
    }

    @Synchronized
    fun previous(context: Context) {
        val tracks = _tracks.value
        if (tracks.isEmpty()) return
        ensureRestored(context)
        val exo = ensurePlayer(context.applicationContext)
        if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
        } else {
            play(context.applicationContext, tracks.size - 1)
        }
    }

    @Synchronized
    fun stop() {
        player?.stop()
        _currentIndex.value = -1
    }

    @Synchronized
    fun release() {
        // Final save before the player goes away (process death, logout, ...).
        appContextRef?.let { saveState(it) }
        stopPeriodicSave()
        player?.release()
        player = null
        _isPlaying.value = false
        _currentIndex.value = -1
    }

    private fun resolveTitle(context: Context, uri: Uri): String {
        // Prefer the display name from the content provider; fall back to the
        // last path segment; last resort a generic label.
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Unknown track"
    }
}
