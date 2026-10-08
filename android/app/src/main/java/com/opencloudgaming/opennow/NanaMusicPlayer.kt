package com.opencloudgaming.opennow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * NanaPlay 1.0.25 — local music player ("play your own music from storage").
 * 1.0.31 — online tracks come from JioSaavn (replacing YouTube InnerTube,
 * which proved unreliable for playback); they resolve to a fresh CDN stream
 * URL at play time.
 * 1.0.36 — playback pipeline rework:
 *   - Restore is metadata-only (no network): online tracks are rebuilt with a
 *     placeholder URI and their stream URL is resolved lazily when the track
 *     is actually played. This fixes "playlist resets every time the app is
 *     closed" (restore used to do one network resolve per track and silently
 *     drop the whole playlist on failure).
 *   - ExoPlayer runs in single-item mode; the queue lives in `_tracks` and
 *     next/previous are implemented manually. This removes the old
 *     add/replaceMediaItem races that made tapping search results feel
 *     unresponsive ("must listen first before it changes song").
 *   - All play requests are serialized through [playMutex] so rapid taps
 *     can't interleave resolves and leave the player in a weird state.
 *   - On playback error for an online track, one automatic retry with a
 *     freshly resolved URL (JioSaavn CDN URLs can expire mid-playback).
 *   - Natural track end auto-advances to the next queued track.
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
    /** 1.0.31: JioSaavn song id for online tracks — used to re-resolve the stream URL on restore. */
    val saavnId: String? = null,
)

object NanaMusicPlayer {

    private var player: androidx.media3.exoplayer.ExoPlayer? = null

    private val _tracks = MutableStateFlow<List<NanaTrack>>(emptyList())
    val tracks: StateFlow<List<NanaTrack>> = _tracks.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** 1.0.28: last ExoPlayer error message, if any — surfaced so playback failures are visible instead of silent. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 1.0.29: true while ExoPlayer is stuck buffering — surfaced so the user can
     * tell "still loading" apart from a hard error. */
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    val currentTrack: NanaTrack?
        get() {
            val idx = _currentIndex.value
            return _tracks.value.getOrNull(idx)
        }

    // ---- 1.0.26: playback state persistence (Spotify-style restore) ----
    // Persists the playlist (local URIs + online saavnIds/metadata), the
    // current track identity, and the playback position. Restored lazily on
    // first player access after a process restart; the user continues paused
    // at the exact track and second where they left off.
    //
    // 1.0.36: restore is metadata-only. Online entries keep their saavnId and
    // get a placeholder URI; the real stream URL is resolved when the track
    // is played (see resolvePlayable). ExoPlayer is not touched during
    // restore, so a slow/dead network can no longer wipe the playlist.
    private const val MUSIC_PREFS = "nana_music_state"
    private const val KEY_STATE_JSON = "state_json"
    private const val SAVE_INTERVAL_MS = 5000L

    /** 1.0.36: scheme marking online tracks whose stream URL isn't resolved yet. Never handed to ExoPlayer. */
    private const val PLACEHOLDER_SCHEME = "saavn"

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var appContextRef: Context? = null
    private var restored = false
    private var restoreJob: Job? = null

    /** 1.0.36: playback position captured at restore; consumed by the first play. */
    private var pendingSeekMs: Long = 0L

    /** 1.0.36: saavnId we already auto-retried after an error (prevents retry loops). */
    private var lastRetrySaavnId: String? = null

    /** 1.0.36: serializes every "start playing" request so rapid taps can't interleave. */
    private val playMutex = Mutex()

    private fun isPlaceholderUri(track: NanaTrack): Boolean =
        track.isOnline && track.uri.scheme == PLACEHOLDER_SCHEME

    /** Launch a one-time async restore on first player access per process. */
    private fun ensureRestored(context: Context) {
        synchronized(this) {
            if (restored) return
            restored = true
        }
        val appCtx = context.applicationContext
        restoreJob = persistScope.launch {
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
                    o.put("saavnId", t.saavnId)
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
                .put("currentSaavnId", if (current?.isOnline == true) current.saavnId else null)
            context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE_JSON, state.toString())
                .apply()
        }
    }

    /**
     * Rebuild the queue from the last saved state — metadata only.
     * Local tracks are re-added only when we still hold a persisted URI
     * permission; online tracks keep their saavnId with a placeholder URI
     * (the stream URL is resolved lazily at play time, so restore never does
     * network I/O and can never fail the whole playlist).
     * ExoPlayer is intentionally NOT touched here.
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
                // 1.0.31: JioSaavn ids. Legacy "videoId" entries are YouTube
                // ids from 1.0.26–1.0.30 — they can't resolve on JioSaavn, so
                // they're skipped gracefully below.
                val saavnId = o.optString("saavnId").takeIf { it.isNotBlank() }
                    ?: o.optString("videoId").takeIf { it.isNotBlank() }
                    ?: continue
                rebuilt.add(
                    NanaTrack(
                        uri = Uri.parse("$PLACEHOLDER_SCHEME://track/$saavnId"),
                        title = o.optString("title", "Unknown"),
                        artist = o.optString("artist").takeIf { it.isNotBlank() },
                        thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        isOnline = true,
                        saavnId = saavnId,
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

        val positionMs = state.optLong("positionMs", 0L)
        val currentUri = state.optString("currentUri").takeIf { it.isNotBlank() }
        val currentSaavnId = state.optString("currentSaavnId").takeIf { it.isNotBlank() }
            ?: state.optString("currentVideoId").takeIf { it.isNotBlank() }
        synchronized(this) {
            // Don't clobber a playlist the user already built this session
            // (e.g. they added tracks while restore was in flight).
            if (_tracks.value.isNotEmpty()) return
            _tracks.value = rebuilt
            val idx = when {
                currentSaavnId != null ->
                    rebuilt.indexOfFirst { it.isOnline && it.saavnId == currentSaavnId }
                currentUri != null ->
                    rebuilt.indexOfFirst { !it.isOnline && it.uri.toString() == currentUri }
                else -> 0
            }.coerceAtLeast(0)
            _currentIndex.value = idx
            pendingSeekMs = positionMs.coerceAtLeast(0L)
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
        // 1.0.31: plain default media source factory. The YouTube-specific
        // Range-forcing DataSource and UA spoofing (1.0.28/1.0.29) are gone —
        // JioSaavn serves direct CDN URLs that stream fine with a normal
        // request (no throttling, no IP binding).
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
                        _lastError.value = null
                        lastRetrySaavnId = null
                        startPeriodicSave()
                    } else {
                        stopPeriodicSave()
                        // Persist position on every pause — cheap and exact.
                        persistScope.launch { saveState(appContext) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("NanaMusicPlayer", "playback error", error)
                    // Surface the HTTP status when the source was rejected to
                    // make future diagnosis easier. The message from ExoPlayer
                    // usually already contains the code.
                    val httpCode: Int? = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>()
                        .mapNotNull { ex ->
                            runCatching {
                                val field = ex.javaClass.getField("responseCode")
                                (field.get(ex) as? Int)?.takeIf { code -> code > 0 }
                            }.getOrNull()
                        }
                        .firstOrNull()
                    _lastError.value = buildString {
                        append(error.message ?: "Playback error")
                        if (httpCode != null) append(" (HTTP $httpCode)")
                    }
                    // 1.0.36: one automatic retry with a freshly resolved URL.
                    // JioSaavn CDN URLs can expire mid-playback ("song dies by
                    // itself"); re-resolving usually brings it back without
                    // the user having to do anything.
                    val saavnId = synchronized(this@NanaMusicPlayer) {
                        currentTrack?.takeIf { it.isOnline }?.saavnId
                    }?.takeIf { it != lastRetrySaavnId } ?: return
                    lastRetrySaavnId = saavnId
                    persistScope.launch {
                        val freshUrl = try {
                            NanaSaavnApi.resolveAudioUrl(saavnId)
                        } catch (e: Exception) {
                            null
                        } ?: return@launch
                        playMutex.withLock {
                            ensureActive()
                            val idx = synchronized(this@NanaMusicPlayer) { _currentIndex.value }
                            val cur = synchronized(this@NanaMusicPlayer) { _tracks.value.getOrNull(idx) }
                            // Only retry if the user hasn't moved on meanwhile.
                            if (cur?.saavnId != saavnId) return@withLock
                            val updated = cur.copy(uri = Uri.parse(freshUrl))
                            synchronized(this@NanaMusicPlayer) {
                                _tracks.value = _tracks.value.toMutableList().also { it[idx] = updated }
                            }
                            val retryExo = player ?: return@withLock
                            val pos = retryExo.currentPosition.coerceAtLeast(0L)
                            retryExo.setMediaItem(MediaItem.fromUri(updated.uri))
                            retryExo.prepare()
                            if (pos > 1000L) retryExo.seekTo(pos)
                            _lastError.value = null
                            retryExo.play()
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _isBuffering.value = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_ENDED) {
                        // 1.0.36: auto-advance on natural completion (standard
                        // music-player behavior). Stops at the end of the
                        // queue; the 1.0.35 replay fix still applies then.
                        val appCtx = appContextRef ?: return
                        persistScope.launch {
                            playMutex.withLock {
                                ensureActive()
                                val nextIdx = synchronized(this@NanaMusicPlayer) {
                                    val size = _tracks.value.size
                                    val cand = _currentIndex.value + 1
                                    if (cand < size) cand else -1
                                }
                                if (nextIdx >= 0) playInternal(appCtx, nextIdx)
                            }
                        }
                    }
                }
            },
        )
        player = exo
        return exo
    }

    /**
     * 1.0.36: resolve [track] to something ExoPlayer can actually play.
     * Local tracks are returned as-is. Online tracks whose URI is still the
     * restore placeholder get a fresh JioSaavn CDN URL; already-resolved
     * tracks are reused (expiry is handled by the onPlayerError retry).
     * Returns null when the URL cannot be resolved.
     */
    private suspend fun resolvePlayable(track: NanaTrack): NanaTrack? {
        if (!track.isOnline) return track
        val saavnId = track.saavnId ?: return null
        if (!isPlaceholderUri(track)) return track
        val url = try {
            NanaSaavnApi.resolveAudioUrl(saavnId)
        } catch (e: Exception) {
            null
        } ?: return null
        return track.copy(uri = Uri.parse(url))
    }

    /**
     * 1.0.36: the single serialized play path. Awaits restore, resolves the
     * stream URL for online tracks, loads it as the player's media item,
     * applies any pending restore seek, and plays. Always calls prepare()
     * (the old replace-without-prepare path is what made tapping search
     * results feel unresponsive). Returns false when the track can't play.
     */
    private suspend fun playInternal(appContext: Context, index: Int): Boolean {
        restoreJob?.join()
        val track = synchronized(this) { _tracks.value.getOrNull(index) } ?: return false
        val playable = resolvePlayable(track) ?: run {
            _lastError.value = "Online music temporarily unavailable"
            return false
        }
        if (playable.uri != track.uri) {
            synchronized(this) {
                _tracks.value.getOrNull(index)?.let { cur ->
                    if (cur.saavnId == playable.saavnId || cur.uri == track.uri) {
                        _tracks.value = _tracks.value.toMutableList().also { it[index] = playable }
                    }
                }
            }
        }
        val exo = ensurePlayer(appContext)
        // Single-item mode: the queue lives in _tracks; ExoPlayer only ever
        // holds the current track. This kills the old add/replace races.
        exo.setMediaItem(MediaItem.fromUri(playable.uri))
        exo.prepare()
        val seekTo = synchronized(this) {
            _currentIndex.value = index
            pendingSeekMs.also { pendingSeekMs = 0L }
        }
        if (seekTo > 1000L) exo.seekTo(seekTo)
        _lastError.value = null
        lastRetrySaavnId = null
        exo.play()
        return true
    }

    /**
     * 1.0.36: run [indexProvider] after restore and play the resulting index,
     * serialized against every other play request.
     */
    private fun serializedPlay(context: Context, indexProvider: () -> Int) {
        ensureRestored(context)
        persistScope.launch {
            playMutex.withLock {
                ensureActive()
                restoreJob?.join()
                val idx = synchronized(this@NanaMusicPlayer) { indexProvider() }
                if (idx >= 0) playInternal(context.applicationContext, idx)
            }
        }
    }

    fun addTracks(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        ensureRestored(context)
        val appContext = context.applicationContext
        val newTracks = uris.map { uri ->
            NanaTrack(uri = uri, title = resolveTitle(appContext, uri))
        }
        val (startIndex, shouldAutoPlay) = synchronized(this) {
            val start = _tracks.value.size
            _tracks.value = _tracks.value + newTracks
            start to (_currentIndex.value < 0 && !_isPlaying.value)
        }
        // Auto-start playing the first newly added track when nothing was playing.
        if (shouldAutoPlay) serializedPlay(appContext) { startIndex }
    }

    fun removeTrack(context: Context, index: Int) {
        val (wasCurrent, newSize) = synchronized(this) {
            val tracks = _tracks.value
            if (index !in tracks.indices) return
            val wasCur = index == _currentIndex.value
            _tracks.value = tracks.toMutableList().also { it.removeAt(index) }
            if (index < _currentIndex.value) _currentIndex.value--
            wasCur to _tracks.value.size
        }
        // Keep the persisted playlist in sync after deletions.
        persistScope.launch { appContextRef?.let { saveState(it) } }
        if (newSize == 0) {
            player?.stop()
            player?.clearMediaItems()
            synchronized(this) { _currentIndex.value = -1 }
        } else if (wasCurrent) {
            // Play whatever slid into the removed position.
            serializedPlay(context) {
                synchronized(this@NanaMusicPlayer) {
                    index.coerceIn(0, _tracks.value.size - 1)
                }
            }
        }
    }

    fun play(context: Context, index: Int) {
        serializedPlay(context) { index }
    }

    /**
     * 1.0.36 — resolve [online] (adding it to the queue when new) and play it
     * through the shared serialized play path. Returns false when the stream
     * URL cannot be resolved (caller shows "Online music temporarily
     * unavailable").
     */
    suspend fun playOnlineTrack(context: Context, online: NanaOnlineTrack): Boolean {
        ensureRestored(context)
        val appContext = context.applicationContext
        return playMutex.withLock {
            restoreJob?.join()
            val index = synchronized(this@NanaMusicPlayer) {
                val existing = _tracks.value.indexOfFirst { it.isOnline && it.saavnId == online.saavnId }
                if (existing >= 0) {
                    existing
                } else {
                    _tracks.value = _tracks.value + NanaTrack(
                        uri = Uri.parse("$PLACEHOLDER_SCHEME://track/${online.saavnId}"),
                        title = online.title,
                        artist = online.artist,
                        thumbnailUrl = online.thumbnailUrl,
                        isOnline = true,
                        saavnId = online.saavnId,
                    )
                    _tracks.value.size - 1
                }
            }
            // playInternal resolves the fresh URL (or reuses this session's).
            playInternal(appContext, index)
        }
    }

    fun togglePlayPause(context: Context) {
        ensureRestored(context)
        val exo = ensurePlayer(context.applicationContext)
        if (exo.isPlaying) {
            exo.pause()
            return
        }
        // 1.0.35: if the track finished, seek back to the start first —
        // play() is a no-op while in STATE_ENDED, which left the play
        // button dead after a track completed.
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.play()
            return
        }
        if (exo.currentMediaItem != null) {
            // Resume the paused item without re-resolving.
            exo.play()
            return
        }
        // Nothing loaded — start the current (or first) track.
        val idx = synchronized(this) { _currentIndex.value.takeIf { it >= 0 } ?: 0 }
        serializedPlay(context) { idx }
    }

    fun next(context: Context) {
        serializedPlay(context) {
            val size = _tracks.value.size
            if (size == 0) -1 else (_currentIndex.value + 1) % size
        }
    }

    fun previous(context: Context) {
        serializedPlay(context) {
            val size = _tracks.value.size
            if (size == 0) -1
            else {
                val cur = _currentIndex.value
                if (cur <= 0) size - 1 else cur - 1
            }
        }
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _currentIndex.value = -1
        synchronized(this) { pendingSeekMs = 0L }
    }

    fun release() {
        // Final save before the player goes away (process death, logout, ...).
        appContextRef?.let { saveState(it) }
        stopPeriodicSave()
        player?.release()
        player = null
        _isPlaying.value = false
        _currentIndex.value = -1
        synchronized(this) { pendingSeekMs = 0L }
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
