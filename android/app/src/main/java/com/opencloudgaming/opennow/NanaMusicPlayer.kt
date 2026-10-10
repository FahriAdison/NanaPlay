package com.opencloudgaming.opennow
import com.papahchan.nanaplay.R

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
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
 * 1.0.43 — hardening pass (no behavior change on the happy path):
 *   - resolvePlayable validates ids/URLs (blank id, empty or unparseable
 *     stream URL) and never lets a bad URI reach ExoPlayer.
 *   - playInternal re-validates the queue index after the network resolve
 *     and re-checks the player instance under lock before touching it.
 *   - addTracks probes every picked URI so one corrupt/unreadable file
 *     can't fail the batch ("X dari Y track gagal ditambahkan").
 *   - restoreState validates every entry and resets a corrupt snapshot to
 *     a safe empty state instead of crashing.
 *   - onPlayerError can never crash: online tracks get one fresh-URL retry,
 *     otherwise the failed track is skipped with a clear message; when no
 *     track is left, playback stops safely.
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

    // ---- 1.0.38: crash catcher ----
    // The 1.0.37 member report ("force close when adding a song") couldn't be
    // diagnosed because Share diagnostics never captured the stack trace.
    // Every public entry point now funnels through [guarded], which catches
    // any throwable, writes a crash log file, and keeps a short summary in
    // memory so it can be included in the next diagnostics export.
    private const val CRASH_DIR = "NanaPlay/crash-logs"
    private const val MAX_CRASH_FILES = 10

    @Volatile
    private var lastCrashSummary: String? = null

    /** Short human-readable summary of the most recent music-player crash, if any. Null when clean. */
    fun lastCrashSummary(): String? = lastCrashSummary

    private fun logCrashToFile(context: Context, tag: String, t: Throwable) {
        val summary = buildString {
            append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            append(" [").append(tag).append("] ")
            append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        lastCrashSummary = summary
        runCatching {
            val dir = java.io.File(context.applicationContext.filesDir, CRASH_DIR).apply { mkdirs() }
            // Prune old logs so the folder can't grow forever.
            dir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                if (files.size >= MAX_CRASH_FILES) {
                    files.take(files.size - MAX_CRASH_FILES + 1).forEach { runCatching { it.delete() } }
                }
            }
            val file = java.io.File(dir, "crash-${System.currentTimeMillis()}.txt")
            val deviceLine = runCatching {
                "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "sdk=${android.os.Build.VERSION.SDK_INT} app=1.0.38"
            }.getOrDefault("device=unknown")
            file.writeText(buildString {
                appendLine(summary)
                appendLine(deviceLine)
                appendLine("--- stack trace ---")
                append(t.stackTraceToString())
                t.cause?.let { cause ->
                    appendLine("--- cause ---")
                    append(cause.stackTraceToString())
                }
            })
        }
        android.util.Log.e("NanaMusicPlayer", "crash [$tag]", t)
    }

    /**
     * 1.0.38: run [block] with a crash net. Any throwable is logged to a
     * crash file (see [logCrashToFile]) and surfaced via [_lastError] instead
     * of force-closing the app.
     */
    private inline fun guarded(context: Context, tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logCrashToFile(context.applicationContext, tag, t)
            _lastError.value = context.getString(R.string.music_err_generic, tag)
        }
    }

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
     *
     * 1.0.43: hardened — every entry is validated before use (blank ids,
     * blank/unparseable URIs, and lost SAF permissions are skipped
     * per-track). A corrupt snapshot (bad JSON, wrong types) resets to a
     * safe empty state: the bad snapshot is deleted, the failure is logged
     * to a crash file, and restore never throws.
     */
    private suspend fun restoreState(context: Context) {
        val prefs = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_STATE_JSON, null) ?: return
        val state = try {
            JSONObject(jsonStr)
        } catch (t: Throwable) {
            // Corrupt snapshot — drop it and start clean instead of crashing.
            logCrashToFile(context, "restoreState", t)
            resetToEmptyState(context)
            _lastError.value = context.getString(R.string.music_err_restore_failed)
            return
        }
        try {
            val arr = state.optJSONArray("tracks")
            if (arr == null || arr.length() == 0) return

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
                            title = o.optString("title").takeIf { it.isNotBlank() } ?: "Unknown",
                            artist = o.optString("artist").takeIf { it.isNotBlank() },
                            thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            isOnline = true,
                            saavnId = saavnId,
                        ),
                    )
                } else {
                    val uriStr = o.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    // Reject unparseable / scheme-less URIs before they reach the queue.
                    val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
                        ?.takeIf { it.scheme?.isNotBlank() == true } ?: continue
                    if (uri.scheme == "content" && !hasPersistedReadPermission(context, uri)) continue
                    rebuilt.add(
                        NanaTrack(
                            uri = uri,
                            title = o.optString("title").takeIf { it.isNotBlank() } ?: "Unknown",
                        ),
                    )
                }
            }
            if (rebuilt.isEmpty()) return

            val positionMs = state.optLong("positionMs", 0L).coerceAtLeast(0L)
            val currentUri = state.optString("currentUri").takeIf { it.isNotBlank() }
            val currentSaavnId = state.optString("currentSaavnId").takeIf { it.isNotBlank() }
                ?: state.optString("currentVideoId").takeIf { it.isNotBlank() }
            synchronized(this) {
                // Don't clobber a playlist the user already built this session
                // (e.g. they added tracks while restore was in flight).
                if (_tracks.value.isNotEmpty()) return
                _tracks.value = rebuilt
                // indexOfFirst only ever returns a valid index or -1; the
                // coerceIn is a final guard so a corrupt snapshot can never
                // park the cursor out of range.
                val idx = when {
                    currentSaavnId != null ->
                        rebuilt.indexOfFirst { it.isOnline && it.saavnId == currentSaavnId }
                    currentUri != null ->
                        rebuilt.indexOfFirst { !it.isOnline && it.uri.toString() == currentUri }
                    else -> 0
                }.coerceIn(0, rebuilt.size - 1)
                _currentIndex.value = idx
                pendingSeekMs = positionMs
            }
        } catch (t: Throwable) {
            logCrashToFile(context, "restoreState", t)
            resetToEmptyState(context)
            _lastError.value = context.getString(R.string.music_err_restore_failed)
        }
    }

    /**
     * 1.0.43: drop a corrupt/partial restore — delete the bad snapshot so it
     * can't poison the next launch, and leave playback state safely empty.
     * Never clears a playlist the user built in the meantime.
     */
    private fun resetToEmptyState(context: Context) {
        runCatching {
            context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_STATE_JSON)
                .apply()
        }
        synchronized(this) {
            if (_tracks.value.isEmpty()) {
                _currentIndex.value = -1
                pendingSeekMs = 0L
            }
        }
    }

    private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        }.getOrDefault(false)
    }

    /**
     * 1.0.38: ExoPlayer MUST be created on a thread with a Looper (it binds
     * its internal handler to the creating thread). persistScope runs on
     * Dispatchers.IO which has no Looper — creating the player there threw
     * and force-closed the app on some devices (member report 1.0.37).
     * Now creation is marshalled to the main thread; callers block briefly
     * via runBlocking-free latch instead.
     */
    private fun ensurePlayer(context: Context): androidx.media3.exoplayer.ExoPlayer {
        synchronized(this) { player?.let { return it } }
        // Already on main? Build directly.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return buildPlayer(context)
        }
        // Otherwise hop to main and wait (bounded) for the player.
        val appContext = context.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<androidx.media3.exoplayer.ExoPlayer>(1)
        val error = arrayOfNulls<Throwable>(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                holder[0] = buildPlayer(appContext)
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        error[0]?.let { throw it }
        return holder[0] ?: throw IllegalStateException("Music player failed to start")
    }

    @Synchronized
    private fun buildPlayer(context: Context): androidx.media3.exoplayer.ExoPlayer {
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
                    // 1.0.43: this callback must NEVER crash the app (ExoPlayer
                    // invokes it on the main thread). All failure handling
                    // lives in handlePlayerError, itself fully guarded — this
                    // is one extra net on top.
                    try {
                        handlePlayerError(appContext, error)
                    } catch (t: Throwable) {
                        runCatching { logCrashToFile(appContext, "onPlayerError", t) }
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
     * 1.0.43: hardened playback-error path. Called from the ExoPlayer
     * listener; never throws (the listener adds one more net on top).
     * - The technical detail (message + HTTP status when the source was
     *   rejected) goes to a crash file for diagnostics; the UI gets a
     *   short, clear message instead of raw exception text.
     * - Online tracks get ONE automatic retry with a freshly resolved URL
     *   (JioSaavn CDN URLs can expire mid-playback — "song dies by itself").
     * - Any other failure skips to the next queued track with a clear
     *   message; when nothing is left to try, playback stops safely.
     */
    private fun handlePlayerError(appContext: Context, error: PlaybackException) {
        try {
            // Surface the HTTP status when the source was rejected to make
            // future diagnosis easier. The message from ExoPlayer usually
            // already contains the code.
            val httpCode: Int? = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>()
                .mapNotNull { ex ->
                    runCatching {
                        val field = ex.javaClass.getField("responseCode")
                        (field.get(ex) as? Int)?.takeIf { code -> code > 0 }
                    }.getOrNull()
                }
                .firstOrNull()
            val failedTitle = (synchronized(this) { currentTrack?.title } ?: "lagu ini")
                .replace('\n', ' ').take(80)
            // Technical detail -> crash file (diagnostics). Never the raw
            // exception text in the UI.
            logCrashToFile(appContext, "onPlayerError: $failedTitle", error)
            if (httpCode != null) {
                android.util.Log.w("NanaMusicPlayer", "playback error HTTP $httpCode for \"$failedTitle\"")
            }

            // 1.0.36: one automatic retry with a freshly resolved URL.
            val retrySaavnId = synchronized(this) {
                currentTrack?.takeIf { it.isOnline }?.saavnId
            }?.takeIf { it != lastRetrySaavnId }

            if (retrySaavnId != null) {
                lastRetrySaavnId = retrySaavnId
                _lastError.value = appContext.getString(R.string.music_err_retrying, failedTitle)
                persistScope.launch {
                    try {
                        val freshUrl = try {
                            NanaSaavnApi.resolveAudioUrl(retrySaavnId)
                        } catch (e: Exception) {
                            logCrashToFile(appContext, "onPlayerError-retry", e)
                            null
                        }
                        // Decide whether to skip only AFTER the mutex is
                        // released (Mutex is not reentrant — the skip path
                        // takes it too).
                        var skipInstead = freshUrl.isNullOrBlank()
                        if (!skipInstead) {
                            playMutex.withLock {
                                ensureActive()
                                val idx = synchronized(this@NanaMusicPlayer) { _currentIndex.value }
                                val cur = synchronized(this@NanaMusicPlayer) { _tracks.value.getOrNull(idx) }
                                val retryExo = synchronized(this@NanaMusicPlayer) { player }
                                if (cur?.saavnId != retrySaavnId || retryExo == null) {
                                    // The user moved on, or the player was
                                    // released meanwhile — don't resurrect it.
                                    skipInstead = true
                                } else {
                                    val updated = cur.copy(uri = Uri.parse(freshUrl!!))
                                    synchronized(this@NanaMusicPlayer) {
                                        if (idx in _tracks.value.indices) {
                                            _tracks.value = _tracks.value.toMutableList()
                                                .also { list -> list[idx] = updated }
                                        }
                                    }
                                    val pos = retryExo.currentPosition.coerceAtLeast(0L)
                                    retryExo.setMediaItem(MediaItem.fromUri(updated.uri))
                                    retryExo.prepare()
                                    if (pos > 1000L) retryExo.seekTo(pos)
                                    _lastError.value = null
                                    retryExo.play()
                                }
                            }
                        }
                        if (skipInstead) skipToNextAfterError(appContext, failedTitle)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        logCrashToFile(appContext, "onPlayerError-retry", t)
                        try {
                            skipToNextAfterError(appContext, failedTitle)
                        } catch (t2: Throwable) {
                            if (t2 is CancellationException) throw t2
                            logCrashToFile(appContext, "onPlayerError-skip", t2)
                        }
                    }
                }
                return
            }

            // No retry available (local track, or the one retry already
            // failed): skip the failed track.
            persistScope.launch {
                try {
                    skipToNextAfterError(appContext, failedTitle)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logCrashToFile(appContext, "onPlayerError-skip", t)
                }
            }
        } catch (t: Throwable) {
            // Absolute last net: a playback-error callback must never crash.
            runCatching { logCrashToFile(appContext, "onPlayerError", t) }
        }
    }

    /**
     * 1.0.43: advance past a failed track — play the next queued track, or
     * stop safely when none is left. Takes [playMutex]; callers must not
     * already hold it. Never throws (except coroutine cancellation).
     */
    private suspend fun skipToNextAfterError(appContext: Context, failedTitle: String) {
        playMutex.withLock {
            ensureActive()
            val nextIdx = synchronized(this@NanaMusicPlayer) {
                val cand = _currentIndex.value + 1
                if (cand in _tracks.value.indices) cand else -1
            }
            if (nextIdx >= 0) {
                _lastError.value = appContext.getString(R.string.music_err_play_failed_next, failedTitle)
                // playInternal is fully guarded; if it fails too, its own
                // onPlayerError will advance further — progress is monotonic,
                // so this always terminates at the end of the queue.
                playInternal(appContext, nextIdx)
            } else {
                // Every track failed (or the queue is empty) — stop safely.
                val exo = synchronized(this@NanaMusicPlayer) { player }
                runCatching { exo?.stop() }
                runCatching { exo?.clearMediaItems() }
                _lastError.value =
                    appContext.getString(R.string.music_err_play_failed_end, failedTitle)
            }
        }
    }

    /**
     * 1.0.36: resolve [track] to something ExoPlayer can actually play.
     * Local tracks are returned as-is. Online tracks whose URI is still the
     * restore placeholder get a fresh JioSaavn CDN URL; already-resolved
     * tracks are reused (expiry is handled by the onPlayerError retry).
     * Returns null when the URL cannot be resolved.
     *
     * 1.0.43: hardened — the resolve is fully guarded. Blank/missing ids and
     * blank/unparseable stream URLs return null (with a log entry and a
     * user-facing message) instead of letting a bad URI reach ExoPlayer.
     */
    private suspend fun resolvePlayable(appContext: Context, track: NanaTrack): NanaTrack? {
        if (!track.isOnline) return track
        val saavnId = track.saavnId?.takeIf { it.isNotBlank() }
        if (saavnId == null) {
            android.util.Log.w("NanaMusicPlayer", "resolvePlayable: online track \"${track.title}\" has no saavnId")
            _lastError.value = appContext.getString(R.string.music_err_online_unavailable)
            return null
        }
        if (!isPlaceholderUri(track)) return track
        val url = try {
            NanaSaavnApi.resolveAudioUrl(saavnId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logCrashToFile(appContext, "resolvePlayable", t)
            null
        }
        if (url.isNullOrBlank()) {
            // Expired/dead CDN URL or a service hiccup — the caller surfaces
            // the friendly message; this is worth a diagnostics entry.
            logCrashToFile(
                appContext,
                "resolvePlayable",
                IllegalStateException("empty stream URL for saavnId=$saavnId"),
            )
            _lastError.value = appContext.getString(R.string.music_err_online_unavailable)
            return null
        }
        // Uri.parse itself doesn't throw, but a scheme-less/blank result
        // would only fail later inside ExoPlayer — reject it here instead.
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
            ?.takeIf { it.scheme?.isNotBlank() == true }
        if (parsed == null) {
            logCrashToFile(
                appContext,
                "resolvePlayable",
                IllegalStateException("unparseable stream URL for saavnId=$saavnId"),
            )
            _lastError.value = appContext.getString(R.string.music_err_online_unavailable)
            return null
        }
        return track.copy(uri = parsed)
    }

    /**
     * 1.0.36: the single serialized play path. Awaits restore, resolves the
     * stream URL for online tracks, loads it as the player's media item,
     * applies any pending restore seek, and plays. Always calls prepare()
     * (the old replace-without-prepare path is what made tapping search
     * results feel unresponsive). Returns false when the track can't play.
     *
     * 1.0.43: hardened — never throws (except coroutine cancellation, which
     * is rethrown so structured concurrency keeps working):
     * - the index is validated before AND after the (network) resolve,
     *   because the queue can shrink while we wait — removeTrack racing a
     *   resolve used to crash on `list[index] = ...`;
     * - the ExoPlayer instance is re-checked under lock right before use,
     *   so a concurrent release() can't leave us touching a dead player;
     * - any unexpected throwable is logged to a crash file and reported as
     *   a short user-facing message instead of force-closing (an uncaught
     *   throw here used to escape through serializedPlay's launch).
     */
    private suspend fun playInternal(appContext: Context, index: Int): Boolean {
        try {
            restoreJob?.join()
            // Guard 1: the index must be valid at entry.
            val track = synchronized(this) {
                if (index !in _tracks.value.indices) return false
                _tracks.value[index]
            }
            val playable = resolvePlayable(appContext, track) ?: run {
                _lastError.value = appContext.getString(R.string.music_err_online_unavailable)
                return false
            }
            // Guard 2: the queue may have changed during the resolve — only
            // overwrite the entry when the index is still valid and it's
            // still the same track we resolved.
            synchronized(this) {
                val cur = _tracks.value.getOrNull(index) ?: return false
                if (playable.uri != track.uri &&
                    (cur.saavnId == playable.saavnId || cur.uri == track.uri)
                ) {
                    _tracks.value = _tracks.value.toMutableList().also { it[index] = playable }
                }
            }
            val exo = ensurePlayer(appContext)
            // Guard 3: release() may have run while we were resolving — never
            // touch a player that is no longer the live instance.
            val liveExo = synchronized(this) { if (player === exo) exo else null }
                ?: run {
                    _lastError.value = appContext.getString(R.string.music_err_cannot_play)
                    return false
                }
            // Single-item mode: the queue lives in _tracks; ExoPlayer only ever
            // holds the current track. This kills the old add/replace races.
            liveExo.setMediaItem(MediaItem.fromUri(playable.uri))
            liveExo.prepare()
            val seekTo = synchronized(this) {
                _currentIndex.value = index
                pendingSeekMs.also { pendingSeekMs = 0L }
            }
            if (seekTo > 1000L) liveExo.seekTo(seekTo)
            _lastError.value = null
            lastRetrySaavnId = null
            liveExo.play()
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logCrashToFile(appContext, "playInternal", t)
            _lastError.value = appContext.getString(R.string.music_err_cannot_play)
            return false
        }
    }

    /**
     * 1.0.36: run [indexProvider] after restore and play the resulting index,
     * serialized against every other play request.
     *
     * 1.0.43: the launch body is crash-netted — a throw here used to escape
     * as an uncaught coroutine exception (force close).
     */
    private fun serializedPlay(context: Context, indexProvider: () -> Int) {
        ensureRestored(context)
        persistScope.launch {
            try {
                playMutex.withLock {
                    ensureActive()
                    restoreJob?.join()
                    val idx = synchronized(this@NanaMusicPlayer) { indexProvider() }
                    if (idx >= 0) playInternal(context.applicationContext, idx)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logCrashToFile(context.applicationContext, "serializedPlay", t)
                _lastError.value = context.getString(R.string.music_err_cannot_play)
            }
        }
    }

    /**
     * 1.0.38: hardened add flow.
     * - URI validation first: skip entries with a blank/null scheme instead of
     *   letting ExoPlayer choke on them later.
     * - Title resolution (content-resolver query) moved off the UI thread —
     *   the file picker callback runs on main and a slow provider used to
     *   jank/ANR here.
     * - The whole body is crash-netted: any failure surfaces via [lastError]
     *   instead of force-closing the app (member report 1.0.37).
     *
     * 1.0.43: per-track hardening — every URI is probed for readability
     * (catches corrupt files, dead providers, and revoked SAF grants) so one
     * bad file can't fail the whole batch. Failures are counted and reported
     * as "X dari Y track gagal ditambahkan".
     */
    fun addTracks(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val appContext = context.applicationContext
        // Validate synchronously (cheap) so obviously-bad URIs never enter the queue.
        val valid = uris.filter { uri ->
            runCatching { uri.scheme?.isNotBlank() == true }.getOrDefault(false)
        }
        if (valid.isEmpty()) {
            _lastError.value = context.getString(R.string.music_err_no_valid_files)
            return
        }
        persistScope.launch {
            try {
                ensureRestored(appContext)
                restoreJob?.join()
                val added = ArrayList<NanaTrack>()
                var failed = 0
                for (uri in valid) {
                    val track = runCatching {
                        probeTrackReadable(appContext, uri)
                        NanaTrack(uri = uri, title = resolveTitle(appContext, uri))
                    }.getOrNull()
                    if (track != null) {
                        added.add(track)
                    } else {
                        failed++
                    }
                }
                if (added.isNotEmpty()) {
                    val (startIndex, shouldAutoPlay) = synchronized(this@NanaMusicPlayer) {
                        val start = _tracks.value.size
                        _tracks.value = _tracks.value + added
                        start to (_currentIndex.value < 0 && !_isPlaying.value)
                    }
                    persistScope.launch { appContextRef?.let { saveState(it) } }
                    // Auto-start playing the first newly added track when nothing was playing.
                    if (shouldAutoPlay) serializedPlay(appContext) { startIndex }
                }
                if (failed > 0) {
                    // One aggregate log entry per batch (not one per file) so
                    // the crash folder isn't spammed when many bad files are picked.
                    logCrashToFile(
                        appContext,
                        "addTracks",
                        IllegalStateException("$failed of ${valid.size} picked tracks unreadable"),
                    )
                    _lastError.value =
                        appContext.getString(R.string.music_err_add_tracks, failed, valid.size)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logCrashToFile(appContext, "addTracks", t)
                _lastError.value = context.getString(R.string.music_err_add_failed)
            }
        }
    }

    /**
     * 1.0.43: verify a picked URI is actually readable before it enters the
     * queue. Throws when the file is corrupt/gone, the provider is dead, or
     * the persisted SAF read grant was revoked — the caller counts the
     * failure instead of letting it poison the batch.
     */
    private fun probeTrackReadable(context: Context, uri: Uri) {
        when (uri.scheme?.lowercase(java.util.Locale.US)) {
            "content" -> {
                // Throws SecurityException / FileNotFoundException (and
                // friends) when the grant is gone or the provider can't
                // serve the file.
                context.contentResolver.openFileDescriptor(uri, "r")?.close()
                    ?: throw java.io.FileNotFoundException("unreadable content URI: $uri")
            }
            "file" -> {
                val f = uri.path?.let { java.io.File(it) }
                    ?: throw java.io.FileNotFoundException("unreadable file URI: $uri")
                if (!f.isFile || !f.canRead()) {
                    throw java.io.FileNotFoundException("unreadable file: ${f.path}")
                }
            }
            // Other schemes (http/https, …): nothing cheap to probe — accept
            // and let playback report any failure per-track.
            else -> Unit
        }
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
        guarded(context, "play") { serializedPlay(context) { index } }
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
        guarded(context, "togglePlayPause") {
            ensureRestored(context)
            val exo = ensurePlayer(context.applicationContext)
            if (exo.isPlaying) {
                exo.pause()
                return@guarded
            }
            // 1.0.35: if the track finished, seek back to the start first —
            // play() is a no-op while in STATE_ENDED, which left the play
            // button dead after a track completed.
            if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0)
                exo.play()
                return@guarded
            }
            if (exo.currentMediaItem != null) {
                // Resume the paused item without re-resolving.
                exo.play()
                return@guarded
            }
            // Nothing loaded — start the current (or first) track.
            val idx = synchronized(this) { _currentIndex.value.takeIf { it >= 0 } ?: 0 }
            serializedPlay(context) { idx }
        }
    }

    fun next(context: Context) {
        guarded(context, "next") {
            serializedPlay(context) {
                val size = _tracks.value.size
                if (size == 0) -1 else (_currentIndex.value + 1) % size
            }
        }
    }

    fun previous(context: Context) {
        guarded(context, "previous") {
            serializedPlay(context) {
                val size = _tracks.value.size
                if (size == 0) -1
                else {
                    val cur = _currentIndex.value
                    if (cur <= 0) size - 1 else cur - 1
                }
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
