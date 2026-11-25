package com.opencloudgaming.opennow
import com.papahchan.nanaplay.R

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

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
enum class NanaRepeatMode {
    OFF,
    ALL,
    ONE,
}

data class NanaTrack(
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val isOnline: Boolean = false,
    /** 1.0.31: Online track id (JioSaavn song id or YouTube Music `ytm:<id>`). */
    val saavnId: String? = null,
    val onlineSource: NanaOnlineMusicSource = NanaOnlineMusicSource.JIOSAAVN,
    val onlineUrl: String? = null,
)

data class NanaPlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

internal sealed class NanaAutoNextAction {
    data object LoopCurrent : NanaAutoNextAction()
    data class PlayQueueIndex(val index: Int) : NanaAutoNextAction()
    data object FetchRelated : NanaAutoNextAction()
    data object Stop : NanaAutoNextAction()
}

internal fun computeNextPlaybackAction(
    tracksSize: Int,
    currentIndex: Int,
    repeatMode: NanaRepeatMode,
    shuffleEnabled: Boolean,
    autoPlayEnabled: Boolean,
    canFetchRelated: Boolean,
    randomIndexProvider: (Int) -> Int = { bound -> kotlin.random.Random.nextInt(bound) },
): NanaAutoNextAction {
    if (tracksSize <= 0) return NanaAutoNextAction.Stop
    return when (repeatMode) {
        NanaRepeatMode.ONE -> NanaAutoNextAction.LoopCurrent
        NanaRepeatMode.ALL -> {
            val next = if (shuffleEnabled && tracksSize > 1) {
                val cand = randomIndexProvider(tracksSize).coerceIn(0, tracksSize - 1)
                if (cand == currentIndex) (currentIndex + 1) % tracksSize else cand
            } else {
                (currentIndex + 1) % tracksSize
            }
            NanaAutoNextAction.PlayQueueIndex(next)
        }
        NanaRepeatMode.OFF -> {
            if (shuffleEnabled && tracksSize > 1) {
                val cand = randomIndexProvider(tracksSize).coerceIn(0, tracksSize - 1)
                val next = if (cand == currentIndex) (currentIndex + 1) % tracksSize else cand
                NanaAutoNextAction.PlayQueueIndex(next)
            } else {
                val next = currentIndex + 1
                when {
                    next in 0 until tracksSize -> NanaAutoNextAction.PlayQueueIndex(next)
                    autoPlayEnabled && canFetchRelated -> NanaAutoNextAction.FetchRelated
                    else -> NanaAutoNextAction.Stop
                }
            }
        }
    }
}

internal fun selectOnlineQueueOrder(
    selected: NanaOnlineTrack,
    searchResults: List<NanaOnlineTrack>,
    maxTransientOnline: Int = 25,
): List<NanaOnlineTrack> {
    val selectedPos = searchResults.indexOfFirst {
        it.saavnId == selected.saavnId && it.source == selected.source
    }
    val trailing = if (selectedPos >= 0) {
        searchResults.drop(selectedPos + 1)
    } else {
        searchResults
    }
    val ordered = ArrayList<NanaOnlineTrack>(maxTransientOnline)
    val seenKeys = HashSet<Pair<NanaOnlineMusicSource, String>>()
    seenKeys.add(selected.source to selected.saavnId)
    ordered.add(selected)
    for (item in trailing) {
        if (ordered.size >= maxTransientOnline) break
        if (seenKeys.add(item.source to item.saavnId)) {
            ordered.add(item)
        }
    }
    return ordered
}

internal fun <T> mergePreservingLocalTracks(
    currentQueue: List<T>,
    isOnline: (T) -> Boolean,
    newOnlineTracks: List<T>,
): Pair<List<T>, Int> {
    val localTracks = currentQueue.filterNot(isOnline)
    return (localTracks + newOnlineTracks) to localTracks.size
}

internal fun pickNextRelatedTrack(
    candidates: List<NanaOnlineTrack>,
    existingOnlineIds: Set<String>,
    recentAutoPlayIds: Collection<String>,
    currentTrackId: String?,
): NanaOnlineTrack? {
    return candidates.firstOrNull { cand ->
        cand.saavnId != currentTrackId &&
            cand.saavnId !in existingOnlineIds &&
            cand.saavnId !in recentAutoPlayIds
    } ?: candidates.firstOrNull { cand ->
        cand.saavnId != currentTrackId && cand.saavnId !in existingOnlineIds
    } ?: candidates.firstOrNull { cand ->
        cand.saavnId != currentTrackId
    }
}

internal fun pickNextRelatedTrack(
    candidates: List<NanaOnlineTrack>,
    currentQueue: List<NanaTrack>,
    recentAutoPlayIds: Collection<String>,
    currentTrackId: String?,
): NanaOnlineTrack? {
    val queueIds = currentQueue
        .asSequence()
        .filter { it.isOnline }
        .mapNotNull { it.saavnId }
        .toHashSet()
    return pickNextRelatedTrack(candidates, queueIds, recentAutoPlayIds, currentTrackId)
}

internal fun shouldClearPlayRequestedOnPause(
    pausedForCall: Boolean,
    playbackSuppressionReason: Int,
): Boolean =
    !pausedForCall &&
        playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

object NanaMusicPlayer {

    @Volatile
    private var player: androidx.media3.exoplayer.ExoPlayer? = null

    private val _tracks = MutableStateFlow<List<NanaTrack>>(emptyList())
    val tracks: StateFlow<List<NanaTrack>> = _tracks.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _repeatMode = MutableStateFlow(NanaRepeatMode.OFF)
    val repeatMode: StateFlow<NanaRepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _autoPlayEnabled = MutableStateFlow(true)
    val autoPlayEnabled: StateFlow<Boolean> = _autoPlayEnabled.asStateFlow()

    private val _playerVolume = MutableStateFlow(1.0f)
    val playerVolume: StateFlow<Float> = _playerVolume.asStateFlow()

    private val _progress = MutableStateFlow(NanaPlaybackProgress())
    val progress: StateFlow<NanaPlaybackProgress> = _progress.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    fun toggleRepeatMode() {
        val next = when (_repeatMode.value) {
            NanaRepeatMode.OFF -> NanaRepeatMode.ALL
            NanaRepeatMode.ALL -> NanaRepeatMode.ONE
            NanaRepeatMode.ONE -> NanaRepeatMode.OFF
        }
        setRepeatMode(next)
    }

    fun setRepeatMode(mode: NanaRepeatMode) {
        _repeatMode.value = mode
        persistScope.launch { appContextRef?.let { saveState(it) } }
    }

    fun toggleShuffle() {
        setShuffleEnabled(!_shuffleEnabled.value)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        persistScope.launch { appContextRef?.let { saveState(it) } }
    }

    fun toggleAutoPlay() {
        setAutoPlayEnabled(!_autoPlayEnabled.value)
    }

    fun setAutoPlayEnabled(enabled: Boolean) {
        _autoPlayEnabled.value = enabled
        persistScope.launch { appContextRef?.let { saveState(it) } }
    }

    fun setPlayerVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _playerVolume.value = clamped
        val exo = synchronized(this) { player }
        if (exo != null) {
            mainScope.launch {
                runCatching { exo.volume = clamped }
            }
        }
        volumeSaveJob?.cancel()
        volumeSaveJob = persistScope.launch {
            delay(400L)
            appContextRef?.let { saveState(it) }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        synchronized(this) {
            pendingSeekMs = clamped
            pendingSeekIndex = _currentIndex.value
        }
        val curDur = _progress.value.durationMs
        _progress.value = NanaPlaybackProgress(
            positionMs = if (curDur > 0L) clamped.coerceAtMost(curDur) else clamped,
            durationMs = curDur,
        )
        val exo = synchronized(this) { player } ?: return
        mainScope.launch {
            runCatching {
                if (exo.currentMediaItem != null) {
                    exo.seekTo(clamped)
                    captureProgress(exo)
                }
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerJob = null
            _sleepTimerRemainingMs.value = null
            return
        }
        var remaining = minutes * 60_000L
        _sleepTimerRemainingMs.value = remaining
        sleepTimerJob = persistScope.launch {
            while (remaining > 0L) {
                delay(1_000L)
                remaining -= 1_000L
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0L)
            }
            _sleepTimerRemainingMs.value = null
            sleepTimerJob = null
            playbackGeneration.incrementAndGet()
            playRequested = false
            pausedForCall = false
            wasPlayingBeforeCall = false
            val exo = synchronized(this@NanaMusicPlayer) { player }
            withContext(Dispatchers.Main) {
                runCatching { exo?.pause() }
            }
            appContextRef?.let { ctx ->
                _lastError.value = ctx.getString(R.string.music_sleep_timer_ended)
            }
        }
    }

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

    // 1.0.102: Ring buffer 50 entri event playback untuk diagnostik (hunt player-stop vs crash)
    private const val MAX_PLAYBACK_HISTORY_ENTRIES = 50
    private val playbackHistory = ArrayDeque<String>(MAX_PLAYBACK_HISTORY_ENTRIES)

    private fun logMusicEvent(event: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(playbackHistory) {
            if (playbackHistory.size >= MAX_PLAYBACK_HISTORY_ENTRIES) {
                playbackHistory.removeFirst()
            }
            playbackHistory.addLast("$timestamp $event")
        }
    }

    fun getPlaybackHistory(): List<String> = synchronized(playbackHistory) {
        playbackHistory.toList()
    }

    private const val LIFECYCLE_LOG_NAME = "nana_music_lifecycle.log"
    private const val MAX_LIFECYCLE_LOG_BYTES = 64 * 1024

    /**
     * Instrumentasi murni (tanpa mengubah perilaku playback): marker lifecycle service musik,
     * dicatat ke ring buffer via [logMusicEvent] AND ke file persisten (ditulis off-thread lewat
     * persistScope). Supaya paste diagnostik pasca-kejadian bisa membedakan "musik berhenti
     * saat proses masih hidup" (ada marker onDestroy/onTaskRemoved, tidak ada record baru di
     * process exit history) vs "proses/service mati" (ada record exit reason pada timestamp
     * yang sama di AndroidProcessExitDiagnostics).
     */
    fun logLifecycleMarker(context: Context, event: String) {
        logMusicEvent(event)
        val appContext = context.applicationContext
        val line = "${System.currentTimeMillis()} $event\n"
        persistScope.launch {
            runCatching {
                val file = java.io.File(appContext.filesDir, LIFECYCLE_LOG_NAME)
                file.appendText(line)
                if (file.length() > MAX_LIFECYCLE_LOG_BYTES) {
                    file.writeText(file.readText().takeLast(MAX_LIFECYCLE_LOG_BYTES / 2))
                }
            }
        }
    }

    fun readLifecycleLog(context: Context): String = runCatching {
        java.io.File(context.applicationContext.filesDir, LIFECYCLE_LOG_NAME).readText()
    }.getOrDefault("")

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

    /** 1.0.59: extension fallback for validateAudioUri when the provider gives no MIME type. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "adts", "ogg", "oga", "opus",
        "flac", "wav", "wave", "mid", "midi", "amr", "awb", "3gp", "3g2",
    )

    /** 1.0.36: scheme marking online tracks whose stream URL isn't resolved yet. Never handed to ExoPlayer. */
    private const val PLACEHOLDER_SCHEME = "nana-online"

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var appContextRef: Context? = null
    private var restored = false
    private var restoreJob: Job? = null
    private var progressTickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var volumeSaveJob: Job? = null
    private val playbackGeneration = AtomicInteger(0)

    private const val MAX_CONSECUTIVE_PLAYBACK_ERRORS = 3
    private const val MAX_RELATED_CACHE_ENTRIES = 20
    private const val MAX_RECENT_AUTOPLAY_IDS = 40
    private val relatedTracksCache = LinkedHashMap<String, List<NanaOnlineTrack>>()
    private val recentAutoPlayIds = ArrayDeque<String>()

    /**
     * 1.0.59 — true while the user wants playback. togglePlayPause/next/play
     * set it; pause/stop clear it. playInternal checks it AFTER the (slow,
     * network) resolve: the user may have paused while resolving, and the
     * resolve must not resurrect playback against their intent. Volatile
     * because it is touched from UI callbacks and IO coroutines.
     */
    @Volatile
    private var playRequested = false

    /** 1.0.59 — call-state auto-pause bookkeeping (see NanaCallStateWatcher). */
    @Volatile
    private var pausedForCall = false

    @Volatile
    private var wasPlayingBeforeCall = false

    /** 1.0.36: playback position captured at restore; consumed by the first play. */
    // 1.0.67 (F-05): read on the IO thread, written on Main — @Volatile
    // avoids torn reads on 32-bit CPUs.
    @Volatile
    private var pendingSeekMs: Long = 0L
    // 1.0.83 (DSH P1#1): track index that pendingSeekMs belongs to. The seek
    // is only applied when starting the SAME index — otherwise a pause
    // position would leak into the next track.
    @Volatile
    private var pendingSeekIndex: Int = -1

    /** 1.0.36: saavnId we already auto-retried after an error (prevents retry loops). */
    private var lastRetrySaavnId: String? = null

    /** 1.0.109: consecutive playback error counter to cap skip-loops and prevent disk log flooding. */
    @Volatile
    private var consecutiveErrorCount = 0

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
        android.util.Log.e("NanaMusicPlayer", "crash [$tag]", t)
        // 1.0.59: file I/O moved off the calling thread. onPlayerError runs on
        // the main thread and mkdirs()/listFiles()/writeText() used to block
        // it (jank/ANR on slow storage or error bursts). Best-effort: if the
        // process dies mid-write, the summary above + logcat still survive.
        val appContext = context.applicationContext
        persistScope.launch {
            runCatching {
                val dir = java.io.File(appContext.filesDir, CRASH_DIR).apply { mkdirs() }
                // Prune old logs so the folder can't grow forever.
                dir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                    if (files.size >= MAX_CRASH_FILES) {
                        files.take(files.size - MAX_CRASH_FILES + 1).forEach { runCatching { it.delete() } }
                    }
                }
                val file = java.io.File(dir, "crash-${System.currentTimeMillis()}.txt")
                val deviceLine = runCatching {
                    "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                        "sdk=${android.os.Build.VERSION.SDK_INT} app=1.0.59"
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
        }
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
        track.isOnline && (track.uri.scheme == PLACEHOLDER_SCHEME || track.uri.scheme == "saavn")

    private fun needsStreamResolve(track: NanaTrack): Boolean {
        if (!track.isOnline) return false
        if (isPlaceholderUri(track)) return true
        return track.onlineSource == NanaOnlineMusicSource.YOUTUBE_MUSIC &&
            NanaYtMusicProvider.isStreamUrlNearExpiry(track.uri.toString())
    }

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

    private fun buildMediaItem(playable: NanaTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(playable.title)
            .setDisplayTitle(playable.title)
            .setArtist(playable.artist?.takeIf { it.isNotBlank() } ?: "NanaPlay")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                playable.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumb ->
                    runCatching { setArtworkUri(Uri.parse(thumb)) }
                }
            }
            .build()
        val mediaId = playable.saavnId ?: playable.uri.toString()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(playable.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Best-effort save of playlist + position. Never throws. */
    private fun saveState(context: Context) {
        runCatching {
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
                    o.put("onlineSource", t.onlineSource.name)
                    if (!t.onlineUrl.isNullOrBlank()) o.put("onlineUrl", t.onlineUrl)
                } else {
                    o.put("uri", t.uri.toString())
                }
                arr.put(o)
            }
            val current = tracks.getOrNull(_currentIndex.value)
            val state = JSONObject()
                .put("tracks", arr)
                .put("positionMs", pendingSeekMs)
                .put("currentUri", if (current?.isOnline == false) current.uri.toString() else null)
                .put("currentSaavnId", if (current?.isOnline == true) current.saavnId else null)
                .put("repeatMode", _repeatMode.value.name)
                .put("shuffleEnabled", _shuffleEnabled.value)
                .put("autoPlayEnabled", _autoPlayEnabled.value)
                .put("playerVolume", _playerVolume.value.toDouble())
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
            val repeatStr = state.optString("repeatMode", "OFF")
            val restoredRepeat = runCatching { NanaRepeatMode.valueOf(repeatStr) }.getOrDefault(NanaRepeatMode.OFF)
            val restoredShuffle = state.optBoolean("shuffleEnabled", false)
            val restoredAutoPlay = state.optBoolean("autoPlayEnabled", true)
            val restoredVolume = state.optDouble("playerVolume", 1.0).toFloat().coerceIn(0f, 1f)
            synchronized(this) {
                _repeatMode.value = restoredRepeat
                _shuffleEnabled.value = restoredShuffle
                _autoPlayEnabled.value = restoredAutoPlay
                _playerVolume.value = restoredVolume
            }
            val liveExo = synchronized(this) { player }
            if (liveExo != null) {
                mainScope.launch { runCatching { liveExo.volume = restoredVolume } }
            }

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
                    val sourceStr = o.optString("onlineSource").takeIf { it.isNotBlank() }
                    val onlineSource = sourceStr?.let {
                        runCatching { NanaOnlineMusicSource.valueOf(it) }.getOrNull()
                    } ?: if (NanaYtMusicProvider.isYoutubeMusicTrackId(saavnId)) {
                        NanaOnlineMusicSource.YOUTUBE_MUSIC
                    } else {
                        NanaOnlineMusicSource.JIOSAAVN
                    }
                    val onlineUrl = o.optString("onlineUrl").takeIf { it.isNotBlank() }
                        ?: if (onlineSource == NanaOnlineMusicSource.YOUTUBE_MUSIC) {
                            NanaYtMusicProvider.resolveYoutubeWatchUrl(saavnId)
                        } else {
                            null
                        }
                    rebuilt.add(
                        NanaTrack(
                            uri = Uri.parse("$PLACEHOLDER_SCHEME://track/${Uri.encode(saavnId)}"),
                            title = o.optString("title").takeIf { it.isNotBlank() } ?: "Unknown",
                            artist = o.optString("artist").takeIf { it.isNotBlank() },
                            thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            isOnline = true,
                            saavnId = saavnId,
                            onlineSource = onlineSource,
                            onlineUrl = onlineUrl,
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
                pendingSeekIndex = idx
                _progress.value = NanaPlaybackProgress(positionMs = positionMs, durationMs = 0L)
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
                pendingSeekIndex = -1
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
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        error[0]?.let { throw it }
        return holder[0] ?: throw IllegalStateException("Music player failed to start")
    }

    private fun captureProgress(exo: androidx.media3.exoplayer.ExoPlayer) {
        val pos = runCatching { exo.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        val rawDur = runCatching { exo.duration }.getOrDefault(C.TIME_UNSET)
        val dur = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else 0L
        _progress.value = NanaPlaybackProgress(positionMs = pos, durationMs = dur)
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = mainScope.launch {
            while (true) {
                val exo = synchronized(this@NanaMusicPlayer) { player }
                if (exo == null || !_isPlaying.value) break
                captureProgress(exo)
                delay(500L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    private fun cacheRelatedTracks(trackId: String, related: List<NanaOnlineTrack>) {
        if (trackId.isBlank() || related.isEmpty()) return
        synchronized(this) {
            relatedTracksCache.remove(trackId)
            relatedTracksCache[trackId] = related
            while (relatedTracksCache.size > MAX_RELATED_CACHE_ENTRIES) {
                val oldestKey = relatedTracksCache.keys.firstOrNull() ?: break
                relatedTracksCache.remove(oldestKey)
            }
        }
    }

    private fun recordRecentAutoPlayId(trackId: String?) {
        val id = trackId?.takeIf { it.isNotBlank() } ?: return
        synchronized(this) {
            recentAutoPlayIds.addLast(id)
            while (recentAutoPlayIds.size > MAX_RECENT_AUTOPLAY_IDS) {
                recentAutoPlayIds.removeFirst()
            }
        }
    }

    private fun onlineToNanaTrack(online: NanaOnlineTrack): NanaTrack = NanaTrack(
        uri = Uri.parse("$PLACEHOLDER_SCHEME://track/${Uri.encode(online.saavnId)}"),
        title = online.title,
        artist = online.artist,
        thumbnailUrl = online.thumbnailUrl,
        isOnline = true,
        saavnId = online.saavnId,
        onlineSource = online.source,
        onlineUrl = online.streamPageUrl,
    )

    @Synchronized
    private fun buildPlayer(context: Context): androidx.media3.exoplayer.ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
        appContextRef = appContext
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // GFN's WebRTC audio module (Streaming.kt / JavaAudioDeviceModule) does
        // NOT request Android audio focus, so enabling handleAudioFocus=true lets
        // music auto-pause for calls / other media apps while mixing cleanly
        // with active GFN game audio.
        val exo = androidx.media3.exoplayer.ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.volume = _playerVolume.value
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val posMs = runCatching { exo.currentPosition }.getOrDefault(-1L)
                    val durMs = runCatching { exo.duration }.getOrDefault(-1L)
                    logMusicEvent("onIsPlayingChanged isPlaying=$isPlaying posMs=$posMs durMs=$durMs")
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        _lastError.value = null
                        lastRetrySaavnId = null
                        playRequested = true
                        consecutiveErrorCount = 0
                        captureProgress(exo)
                        startProgressTicker()
                    } else {
                        stopProgressTicker()
                        if (shouldClearPlayRequestedOnPause(pausedForCall, exo.playbackSuppressionReason)) {
                            playRequested = false
                        }
                        // Persist position on every pause — cheap and exact.
                        // F-17: capture on main thread; saveState() runs on IO
                        // where ExoPlayer.currentPosition would throw.
                        // 1.0.83 (DSH P1#1): also capture the track index so
                        // the position is only restored for the same track.
                        pendingSeekMs = runCatching { exo.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                        pendingSeekIndex = _currentIndex.value
                        captureProgress(exo)
                        persistScope.launch { saveState(appContext) }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    captureProgress(exo)
                }

                override fun onPlayerError(error: PlaybackException) {
                    logMusicEvent("onPlayerError code=${error.errorCode} name=${error.errorCodeName} message=${error.message}")
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
                    val stateName = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "STATE_$playbackState"
                    }
                    val posMs = runCatching { exo.currentPosition }.getOrDefault(-1L)
                    logMusicEvent("onPlaybackStateChanged state=$stateName posMs=$posMs")
                    _isBuffering.value = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) {
                        consecutiveErrorCount = 0
                        captureProgress(exo)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        stopProgressTicker()
                        val appCtx = appContextRef ?: return
                        val endedGen = playbackGeneration.get()
                        persistScope.launch {
                            playMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                if (playbackGeneration.get() != endedGen) return@withLock
                                val (action, curTrack) = synchronized(this@NanaMusicPlayer) {
                                    val tracks = _tracks.value
                                    val curIdx = _currentIndex.value
                                    val cur = tracks.getOrNull(curIdx)
                                    val act = computeNextPlaybackAction(
                                        tracksSize = tracks.size,
                                        currentIndex = curIdx,
                                        repeatMode = _repeatMode.value,
                                        shuffleEnabled = _shuffleEnabled.value,
                                        autoPlayEnabled = _autoPlayEnabled.value,
                                        canFetchRelated = cur?.isOnline == true &&
                                            cur.onlineSource == NanaOnlineMusicSource.YOUTUBE_MUSIC,
                                    )
                                    act to cur
                                }
                                when (action) {
                                    NanaAutoNextAction.LoopCurrent -> {
                                        playRequested = true
                                        withContext(Dispatchers.Main) {
                                            val curExo = player
                                            curExo?.seekTo(0)
                                            curExo?.play()
                                        }
                                    }
                                    is NanaAutoNextAction.PlayQueueIndex -> {
                                        playRequested = true
                                        playInternal(appCtx, action.index)
                                    }
                                    NanaAutoNextAction.FetchRelated -> {
                                        val nextIndex = resolveAndAppendRelatedTrack(curTrack, endedGen)
                                        if (nextIndex >= 0 && playbackGeneration.get() == endedGen) {
                                            playRequested = true
                                            playInternal(appCtx, nextIndex)
                                        } else {
                                            stopAfterPlaybackEnded(appCtx)
                                        }
                                    }
                                    NanaAutoNextAction.Stop -> {
                                        stopAfterPlaybackEnded(appCtx)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
        player = exo
        // 1.0.59: pause music during phone calls. Audio focus is intentionally
        // off (music must mix with game audio), so the system won't pause us
        // by itself — NanaCallStateWatcher does it explicitly. No-op without
        // the READ_PHONE_STATE permission (see its docs).
        NanaCallStateWatcher.register(appContext)
        return exo
    }

    private suspend fun stopAfterPlaybackEnded(appCtx: Context) {
        playRequested = false
        _isPlaying.value = false
        withContext(Dispatchers.Main) {
            runCatching { player?.playWhenReady = false }
        }
        runCatching { NanaMusicPlaybackService.stop(appCtx) }
    }

    private suspend fun resolveAndAppendRelatedTrack(
        curTrack: NanaTrack?,
        endedGen: Int,
    ): Int {
        val currentId = curTrack?.saavnId?.takeIf { it.isNotBlank() } ?: return -1
        val target = curTrack.onlineUrl?.takeIf { it.isNotBlank() } ?: currentId
        val cached = synchronized(this) { relatedTracksCache[currentId] }
        val candidates = if (!cached.isNullOrEmpty()) {
            cached
        } else {
            try {
                NanaYtMusicProvider.fetchRelatedSongs(target, limit = 5).also {
                    cacheRelatedTracks(currentId, it)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
        }
        if (candidates.isEmpty() || playbackGeneration.get() != endedGen) return -1
        return synchronized(this) {
            val next = pickNextRelatedTrack(
                candidates = candidates,
                currentQueue = _tracks.value,
                recentAutoPlayIds = recentAutoPlayIds,
                currentTrackId = currentId,
            ) ?: return@synchronized -1
            recordRecentAutoPlayId(next.saavnId)
            val updatedQueue = _tracks.value + onlineToNanaTrack(next)
            _tracks.value = updatedQueue
            updatedQueue.size - 1
        }
    }

    /**
     * 1.0.59: player accessor for NanaMusicPlaybackService. The service wraps
     * this player in a MediaSession; ownership stays with this singleton.
     */
    fun obtainServicePlayer(context: Context): androidx.media3.exoplayer.ExoPlayer =
        ensurePlayer(context.applicationContext)

    fun isPlaybackRequested(): Boolean = playRequested

    fun canGoNext(): Boolean = _tracks.value.isNotEmpty()

    fun canGoPrevious(): Boolean = _tracks.value.isNotEmpty()

    fun clearQueue(context: Context) {
        guarded(context, "clearQueue") {
            playbackGeneration.incrementAndGet()
            playRequested = false
            pausedForCall = false
            wasPlayingBeforeCall = false
            stopProgressTicker()
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            _sleepTimerRemainingMs.value = null
            val exo = synchronized(this) { player }
            persistScope.launch(Dispatchers.Main) {
                runCatching {
                    exo?.playWhenReady = false
                    exo?.stop()
                    exo?.clearMediaItems()
                }
                appContextRef?.let { runCatching { NanaMusicPlaybackService.stop(it) } }
            }
            synchronized(this) {
                _tracks.value = emptyList()
                _currentIndex.value = -1
                pendingSeekMs = 0L
                pendingSeekIndex = -1
                _isPlaying.value = false
                _progress.value = NanaPlaybackProgress()
            }
            resetToEmptyState(context)
        }
    }

    /** 1.0.59: let UI layers surface a message without going through playback. */
    fun setLastError(message: String?) {
        _lastError.value = message
    }

    /**
     * 1.0.59: called by NanaCallStateWatcher when a call starts ringing or
     * connects. Pauses only if we were actually playing; remembers that the
     * pause was ours so resumeAfterCall() can undo exactly that.
     */
    fun pauseForCall() {
        val exo = synchronized(this) { player }
        if (exo == null) {
            logMusicEvent("pauseForCall ignored (player=null)")
            return
        }
        val isPlaying = exo.isPlaying
        logMusicEvent("pauseForCall isPlaying=$isPlaying")
        if (isPlaying) {
            wasPlayingBeforeCall = true
            pausedForCall = true
            playRequested = false
            runCatching { exo.pause() }
        }
    }

    /**
     * 1.0.59: called when the call ends. Resumes only if WE paused for the
     * call — a manual user pause in the meantime cancels the auto-resume
     * (togglePlayPause clears pausedForCall).
     */
    fun resumeAfterCall() {
        logMusicEvent("resumeAfterCall pausedForCall=$pausedForCall wasPlayingBeforeCall=$wasPlayingBeforeCall")
        if (!pausedForCall) return
        pausedForCall = false
        if (wasPlayingBeforeCall) {
            wasPlayingBeforeCall = false
            playRequested = true
            runCatching { synchronized(this) { player }?.play() }
        } else {
            wasPlayingBeforeCall = false
        }
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
            // 1.0.59: .take(20) guards against a (pathological) cyclic cause
            // chain hanging the main thread forever.
            val httpCode: Int? = generateSequence<Throwable>(error) { it.cause }
                .take(20)
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>()
                .mapNotNull { ex ->
                    // 1.0.59: responseCode only exists on the subclass — the
                    // old reflective getField() never matched anything.
                    (ex as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        ?.responseCode?.takeIf { code -> code > 0 }
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
            val retryTrack = synchronized(this) {
                currentTrack?.takeIf { it.isOnline && !it.saavnId.isNullOrBlank() }
            }?.takeIf { it.saavnId != lastRetrySaavnId }

            if (retryTrack != null) {
                val retryId = retryTrack.saavnId!!
                lastRetrySaavnId = retryId
                _lastError.value = appContext.getString(R.string.music_err_retrying, failedTitle)
                persistScope.launch {
                    try {
                        val freshUrl = try {
                            resolveFreshStreamUrl(retryTrack)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logCrashToFile(appContext, "onPlayerError-retry", e)
                            null
                        }
                        // Decide whether to skip only AFTER the mutex is
                        // released (Mutex is not reentrant — the skip path
                        // takes it too).
                        var skipInstead = freshUrl.isNullOrBlank()
                        if (!skipInstead) {
                            playMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                val idx = synchronized(this@NanaMusicPlayer) { _currentIndex.value }
                                val cur = synchronized(this@NanaMusicPlayer) { _tracks.value.getOrNull(idx) }
                                val retryExo = synchronized(this@NanaMusicPlayer) { player }
                                if (cur?.saavnId != retryId ||
                                    cur.onlineSource != retryTrack.onlineSource ||
                                    retryExo == null
                                ) {
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
                                    // 1.0.67 (A-05): ExoPlayer must be touched on the
                                    // Main thread — currentPosition/setMediaItem/
                                    // prepare/seekTo/play all throw
                                    // IllegalStateException from Dispatchers.IO.
                                    withContext(Dispatchers.Main) {
                                        val pos = retryExo.currentPosition.coerceAtLeast(0L)
                                        retryExo.setMediaItem(buildMediaItem(updated))
                                        retryExo.prepare()
                                        if (pos > 1000L) retryExo.seekTo(pos)
                                        _lastError.value = null
                                        playRequested = true
                                        retryExo.play()
                                        runCatching { NanaMusicPlaybackService.start(appContext) }
                                    }
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
            currentCoroutineContext().ensureActive()
            val (nextIdx, reachedLimit) = synchronized(this@NanaMusicPlayer) {
                consecutiveErrorCount++
                val limit = minOf(_tracks.value.size.coerceAtLeast(1), MAX_CONSECUTIVE_PLAYBACK_ERRORS)
                val reached = consecutiveErrorCount >= limit
                val cand = _currentIndex.value + 1
                (if (cand in _tracks.value.indices) cand else -1) to reached
            }
            if (reachedLimit) {
                consecutiveErrorCount = 0
                playRequested = false
                _isPlaying.value = false
                val exo = synchronized(this@NanaMusicPlayer) { player }
                withContext(Dispatchers.Main) {
                    runCatching {
                        exo?.playWhenReady = false
                        exo?.stop()
                        exo?.clearMediaItems()
                    }
                }
                runCatching { NanaMusicPlaybackService.stop(appContext) }
                _lastError.value = appContext.getString(R.string.music_err_consecutive_failures)
                return@withLock
            }
            if (nextIdx >= 0) {
                _lastError.value = appContext.getString(R.string.music_err_play_failed_next, failedTitle)
                val success = playInternal(appContext, nextIdx)
                if (!success) {
                    playRequested = false
                    _isPlaying.value = false
                    val exo = synchronized(this@NanaMusicPlayer) { player }
                    withContext(Dispatchers.Main) {
                        runCatching {
                            exo?.playWhenReady = false
                            exo?.stop()
                            exo?.clearMediaItems()
                        }
                    }
                    runCatching { NanaMusicPlaybackService.stop(appContext) }
                }
            } else {
                consecutiveErrorCount = 0
                playRequested = false
                _isPlaying.value = false
                val exo = synchronized(this@NanaMusicPlayer) { player }
                withContext(Dispatchers.Main) {
                    runCatching {
                        exo?.playWhenReady = false
                        exo?.stop()
                        exo?.clearMediaItems()
                    }
                }
                runCatching { NanaMusicPlaybackService.stop(appContext) }
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
        if (!needsStreamResolve(track)) return track
        // 1.0.59: the resolve below is a network call (up to ~35s on bad
        // networks). Surface it as buffering so the UI doesn't look dead —
        // the online tab already renders _isBuffering.
        _isBuffering.value = true
        try {
            return resolveOnlineUrl(appContext, track, saavnId)
        } finally {
            _isBuffering.value = false
        }
    }

    private suspend fun resolveFreshStreamUrl(track: NanaTrack): String? = withContext(Dispatchers.IO) {
        val trackId = track.saavnId?.takeIf { it.isNotBlank() } ?: return@withContext null
        when (track.onlineSource) {
            NanaOnlineMusicSource.YOUTUBE_MUSIC -> {
                val target = track.onlineUrl?.takeIf { it.isNotBlank() } ?: trackId
                val resolved = NanaYtMusicProvider.resolveAudioStreamWithRelated(target, maxRelated = 5)
                    ?: return@withContext null
                cacheRelatedTracks(trackId, resolved.relatedTracks)
                resolved.streamUrl
            }
            NanaOnlineMusicSource.JIOSAAVN -> NanaSaavnApi.resolveAudioUrl(trackId)
        }
    }

    /** 1.0.59: network half of resolvePlayable, extracted for the buffering wrapper above. */
    private suspend fun resolveOnlineUrl(
        appContext: Context,
        track: NanaTrack,
        saavnId: String,
    ): NanaTrack? {
        val url = try {
            resolveFreshStreamUrl(track)
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
            val stillMatches = synchronized(this) {
                val cur = _tracks.value.getOrNull(index) ?: return false
                val match = (cur.saavnId != null && cur.saavnId == playable.saavnId && cur.onlineSource == playable.onlineSource) ||
                    cur.uri == track.uri
                if (match && playable.uri != track.uri) {
                    _tracks.value = _tracks.value.toMutableList().also { it[index] = playable }
                }
                match
            }
            if (!stillMatches) return false
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
            // 1.0.67 (F-03): ExoPlayer's verifyApplicationThread() throws
            // IllegalStateException when touched from Dispatchers.IO — all
            // direct player calls must run on the Main thread.
            val seekTo = synchronized(this) {
                _currentIndex.value = index
                // 1.0.83 (DSH P1#1): only restore the saved position when it
                // belongs to this same track index. A pause position from a
                // previous track must never leak into the next one.
                val restoreMs = if (pendingSeekIndex == index) pendingSeekMs else 0L
                pendingSeekMs = 0L
                pendingSeekIndex = -1
                _progress.value = NanaPlaybackProgress(positionMs = restoreMs, durationMs = 0L)
                restoreMs
            }
            val shouldPlay = playRequested
            withContext(Dispatchers.Main) {
                liveExo.setMediaItem(buildMediaItem(playable))
                liveExo.prepare()
                if (seekTo > 1000L) liveExo.seekTo(seekTo)
                if (shouldPlay) {
                    _lastError.value = null
                    lastRetrySaavnId = null
                    liveExo.play()
                }
            }
            // 1.0.59: the user may have paused while the (network) resolve was
            // in flight — togglePlayPause doesn't take playMutex, so a stale
            // play() here would resurrect playback against their intent.
            // The track stays loaded & selected, just not playing.
            if (!shouldPlay) {
                return true
            }
            // 1.0.59: keep the process alive in the background + show system
            // media controls. Started on every real play; harmless if already
            // running. Wrapped: starting an FGS from background is illegal,
            // and we'd rather skip the service than crash.
            runCatching { NanaMusicPlaybackService.start(appContext) }
            // Event-driven save (replaces the old 5s periodic save).
            persistScope.launch { saveState(appContext) }
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
        playRequested = true
        val gen = playbackGeneration.incrementAndGet()
        persistScope.launch {
            try {
                playMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    restoreJob?.join()
                    if (playbackGeneration.get() != gen) return@withLock
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
                        validateAudioUri(appContext, uri)
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

    /**
     * 1.0.59: reject obvious non-audio files. The picker's audio MIME filter
     * isn't honored by every file manager, so a video picked by
     * mistake used to enter the queue and fail only at playback. Throws —
     * the caller counts it in the "X of Y failed" message.
     */
    private fun validateAudioUri(context: Context, uri: Uri) {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null) {
            if (!mime.startsWith("audio/", ignoreCase = true)) {
                throw IllegalArgumentException("not an audio file (MIME $mime): $uri")
            }
            return
        }
        // Provider gave no MIME type — fall back to the file extension.
        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(java.util.Locale.US)
        if (ext.isNullOrEmpty() || ext !in AUDIO_EXTENSIONS) {
            throw IllegalArgumentException("not an audio file: $uri")
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
            // 1.0.59: nothing left to play — stop exo before stopping the foreground service.
            playRequested = false
            persistScope.launch(Dispatchers.Main) {
                val exo = synchronized(this@NanaMusicPlayer) { player }
                runCatching {
                    exo?.playWhenReady = false
                    exo?.stop()
                    exo?.clearMediaItems()
                }
                appContextRef?.let { runCatching { NanaMusicPlaybackService.stop(it) } }
            }
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
    suspend fun playOnlineTrack(context: Context, online: NanaOnlineTrack): Boolean =
        playOnlineSearchSelection(context, online, emptyList())

    suspend fun playOnlineSearchSelection(
        context: Context,
        online: NanaOnlineTrack,
        searchResults: List<NanaOnlineTrack>,
    ): Boolean {
        ensureRestored(context)
        val appContext = context.applicationContext
        playRequested = true
        val gen = playbackGeneration.incrementAndGet()
        return playMutex.withLock {
            restoreJob?.join()
            if (playbackGeneration.get() != gen) return@withLock false
            val selectedTrack = onlineToNanaTrack(online)
            val index = synchronized(this@NanaMusicPlayer) {
                if (searchResults.isEmpty()) {
                    val existing = _tracks.value.indexOfFirst {
                        it.isOnline && it.saavnId == online.saavnId && it.onlineSource == online.source
                    }
                    if (existing >= 0) {
                        existing
                    } else {
                        _tracks.value = _tracks.value + selectedTrack
                        _tracks.value.size - 1
                    }
                } else {
                    val orderedOnline = selectOnlineQueueOrder(online, searchResults)
                        .map(::onlineToNanaTrack)
                    val (newQueue, selectedIdx) = mergePreservingLocalTracks(
                        currentQueue = _tracks.value,
                        isOnline = { it.isOnline },
                        newOnlineTracks = orderedOnline,
                    )
                    _tracks.value = newQueue
                    selectedIdx
                }
            }
            // playInternal resolves the fresh URL (or reuses this session's).
            playInternal(appContext, index)
        }
    }

    fun togglePlayPause(context: Context) {
        guarded(context, "togglePlayPause") {
            ensureRestored(context)
            if (_tracks.value.isEmpty()) return@guarded
            val appContext = context.applicationContext
            val exo = ensurePlayer(appContext)
            if (exo.isPlaying) {
                playbackGeneration.incrementAndGet()
                playRequested = false
                // 1.0.59: a manual pause cancels any pending call auto-resume.
                pausedForCall = false
                wasPlayingBeforeCall = false
                exo.pause()
                return@guarded
            }
            val curIdx = synchronized(this) { _currentIndex.value.takeIf { it >= 0 } ?: 0 }
            val curTrack = synchronized(this) { _tracks.value.getOrNull(curIdx) }
            // Re-resolve online track transparently when stream URL expired while paused.
            if (curTrack != null && needsStreamResolve(curTrack)) {
                val resumePos = if (exo.playbackState == Player.STATE_ENDED) 0L else exo.currentPosition.coerceAtLeast(0L)
                synchronized(this) {
                    pendingSeekMs = resumePos
                    pendingSeekIndex = curIdx
                }
                serializedPlay(context) { curIdx }
                return@guarded
            }
            // 1.0.35: if the track finished, seek back to the start first —
            // play() is a no-op while in STATE_ENDED, which left the play
            // button dead after a track completed.
            if (exo.playbackState == Player.STATE_ENDED) {
                playRequested = true
                exo.seekTo(0)
                exo.play()
                runCatching { NanaMusicPlaybackService.start(appContext) }
                return@guarded
            }
            if (exo.currentMediaItem != null) {
                // Resume the paused item without re-resolving.
                playRequested = true
                exo.play()
                runCatching { NanaMusicPlaybackService.start(appContext) }
                return@guarded
            }
            // Nothing loaded — start the current (or first) track.
            serializedPlay(context) { curIdx }
        }
    }

    fun next(context: Context) {
        guarded(context, "next") {
            ensureRestored(context)
            playRequested = true
            val gen = playbackGeneration.incrementAndGet()
            val appContext = context.applicationContext
            persistScope.launch {
                try {
                    playMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        restoreJob?.join()
                        if (playbackGeneration.get() != gen) return@withLock
                        val (nextIdx, curTrack, shouldFetchRelated) = synchronized(this@NanaMusicPlayer) {
                            val tracks = _tracks.value
                            val size = tracks.size
                            val curIdx = _currentIndex.value
                            val cur = tracks.getOrNull(curIdx)
                            if (size <= 0) {
                                Triple(-1, null, false)
                            } else if (_shuffleEnabled.value && size > 1) {
                                var rand = kotlin.random.Random.nextInt(size)
                                if (rand == curIdx) rand = (curIdx + 1) % size
                                Triple(rand, cur, false)
                            } else if (curIdx + 1 < size) {
                                Triple(curIdx + 1, cur, false)
                            } else if (
                                _repeatMode.value == NanaRepeatMode.OFF &&
                                _autoPlayEnabled.value &&
                                cur?.isOnline == true &&
                                cur.onlineSource == NanaOnlineMusicSource.YOUTUBE_MUSIC
                            ) {
                                Triple(-1, cur, true)
                            } else {
                                Triple(0, cur, false)
                            }
                        }
                        val targetIdx = if (shouldFetchRelated) {
                            resolveAndAppendRelatedTrack(curTrack, gen).takeIf { it >= 0 }
                                ?: if (_tracks.value.isNotEmpty()) 0 else -1
                        } else {
                            nextIdx
                        }
                        if (targetIdx >= 0 && playbackGeneration.get() == gen) {
                            playInternal(appContext, targetIdx)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logCrashToFile(appContext, "next", t)
                    _lastError.value = appContext.getString(R.string.music_err_cannot_play)
                }
            }
        }
    }

    fun previous(context: Context) {
        guarded(context, "previous") {
            val exo = synchronized(this) { player }
            val pos = runCatching { exo?.currentPosition }.getOrNull() ?: 0L
            val wasPlaying = exo?.isPlaying == true
            if (exo != null && exo.currentMediaItem != null && pos > 3000L) {
                mainScope.launch {
                    runCatching {
                        exo.seekTo(0)
                        captureProgress(exo)
                        if (wasPlaying) exo.play()
                    }
                }
                return@guarded
            }
            serializedPlay(context) {
                val size = _tracks.value.size
                if (size <= 0) -1
                else {
                    val cur = _currentIndex.value
                    if (cur <= 0) size - 1 else cur - 1
                }
            }
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        playRequested = false
        pausedForCall = false
        wasPlayingBeforeCall = false
        stopProgressTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
        persistScope.launch(Dispatchers.Main) {
            val exo = synchronized(this@NanaMusicPlayer) { player }
            runCatching {
                exo?.playWhenReady = false
                exo?.stop()
                exo?.clearMediaItems()
            }
            appContextRef?.let { runCatching { NanaMusicPlaybackService.stop(it) } }
        }
        _currentIndex.value = -1
        _progress.value = NanaPlaybackProgress()
        synchronized(this) { pendingSeekMs = 0L; pendingSeekIndex = -1 }
    }

    // 1.0.67 (F-05): synchronize with playInternal's Guard 3 — a concurrent
    // release() must not null out `player` while playInternal is using it.
    @Synchronized
    fun release(stopService: Boolean = true) {
        // Final save before the player goes away (process death, logout, ...).
        appContextRef?.let { saveState(it) }
        playbackGeneration.incrementAndGet()
        playRequested = false
        pausedForCall = false
        wasPlayingBeforeCall = false
        stopProgressTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        volumeSaveJob?.cancel()
        volumeSaveJob = null
        _sleepTimerRemainingMs.value = null
        appContextRef?.let {
            if (stopService) {
                runCatching { NanaMusicPlaybackService.stop(it) }
            }
            NanaCallStateWatcher.unregister(it)
        }
        val exo = player
        player = null
        persistScope.launch(Dispatchers.Main) {
            runCatching { exo?.release() }
        }
        _isPlaying.value = false
        _currentIndex.value = -1
        _progress.value = NanaPlaybackProgress()
        synchronized(this) { pendingSeekMs = 0L; pendingSeekIndex = -1 }
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
