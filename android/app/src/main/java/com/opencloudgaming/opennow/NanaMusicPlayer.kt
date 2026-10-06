package com.opencloudgaming.opennow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NanaPlay 1.0.25 — local music player ("play your own music from storage").
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

    @Synchronized
    private fun ensurePlayer(context: Context): androidx.media3.exoplayer.ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
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
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentIndex.value = exo.currentMediaItemIndex
                }
            },
        )
        player = exo
        return exo
    }

    @Synchronized
    fun addTracks(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
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
    }

    @Synchronized
    fun play(context: Context, index: Int) {
        val tracks = _tracks.value
        if (index !in tracks.indices) return
        val exo = ensurePlayer(context.applicationContext)
        _currentIndex.value = index
        exo.seekTo(index, 0)
        exo.play()
    }

    @Synchronized
    fun togglePlayPause(context: Context) {
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
