package com.opencloudgaming.opennow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette
import kotlinx.coroutines.launch

private fun musicReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * 1.0.27 — hoisted online-search state. Previously the query/results lived in
 * `remember` inside NanaMusicOnlineTab, so switching tabs destroyed the tab
 * composable and the search reset. Hoisted to NanaMusicScreen (which stays
 * composed across tab switches) so results survive.
 */
class NanaOnlineSearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<NanaOnlineTrack>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var playingVideoId by mutableStateOf<String?>(null)
}

/**
 * NanaPlay 1.0.25 — standalone music screen (outside a stream).
 * 1.0.26 — two tabs: "My Music" (local files, unchanged) and "Online"
 * (YouTube Music search via InnerTube, inspired by Metrolist).
 *
 * Simple local music player: pick audio files from storage, play/pause/next/
 * previous, playlist with per-track delete. UI copy stays in English.
 */
@Composable
fun NanaMusicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tracks by NanaMusicPlayer.tracks.collectAsState()
    val isPlaying by NanaMusicPlayer.isPlaying.collectAsState()
    val currentIndex by NanaMusicPlayer.currentIndex.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    val onlineSearchState = remember { NanaOnlineSearchState() }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                musicReadPermission(),
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Persist access so tracks survive process restarts within the grant.
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            NanaMusicPlayer.addTracks(context, uris)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = OpenNowPalette.AccentDefault,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Music",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Text(
                    if (tab == 0) "Play your own music from storage"
                    else "Search and play music online",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tab == 0) {
                OutlinedButton(
                    onClick = {
                        if (!permissionGranted) {
                            permissionLauncher.launch(musicReadPermission())
                        } else {
                            filePicker.launch(arrayOf("audio/*"))
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Add", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("My Music") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Online") },
            )
        }

        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            // 1.0.27: "My Music" shows local files only. Online tracks stay in
            // the shared player queue but must not appear here — entries carry
            // their real playlist index so play/remove still target correctly.
            val localEntries = remember(tracks) {
                tracks.withIndex().filter { !it.value.isOnline }
            }
            NanaMusicLocalTab(
                entries = localEntries,
                isPlaying = isPlaying,
                currentIndex = currentIndex,
                nowPlayingTitle = tracks.getOrNull(currentIndex)?.title,
                onPlay = { NanaMusicPlayer.play(context, it) },
                onRemove = { NanaMusicPlayer.removeTrack(context, it) },
                onToggle = { NanaMusicPlayer.togglePlayPause(context) },
                onNext = { NanaMusicPlayer.next(context) },
                onPrevious = { NanaMusicPlayer.previous(context) },
                modifier = Modifier.weight(1f),
            )
        } else {
            NanaMusicOnlineTab(
                state = onlineSearchState,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NanaMusicLocalTab(
    entries: List<IndexedValue<NanaTrack>>,
    isPlaying: Boolean,
    currentIndex: Int,
    nowPlayingTitle: String?,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        "No music yet",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Tap Add to pick audio files from your storage. " +
                            "Music keeps playing even while you stream a game.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> "${entry.index}-${entry.value.uri}" }) { _, entry ->
                    val index = entry.index
                    val track = entry.value
                    val selected = index == currentIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(index) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            OpenNowPalette.AccentDefault.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (track.thumbnailUrl != null) {
                                AsyncImage(
                                    model = track.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Icon(
                                    imageVector = if (selected && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (selected) OpenNowPalette.AccentDefault
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOfNotNull(
                                    track.artist,
                                    if (track.isOnline) "Online" else null,
                                ).joinToString(" • ").takeIf { it.isNotBlank() }
                                if (subtitle != null) {
                                    Text(
                                        subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Now-playing control bar
            NanaMusicControlBar(
                title = nowPlayingTitle ?: "Select a track",
                isPlaying = isPlaying,
                hasTracks = entries.isNotEmpty(),
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
            )
        }
    }
}

/**
 * NanaPlay 1.0.26 — online music tab: search YouTube Music via InnerTube,
 * tap a result to resolve its audio stream and play it through the shared
 * ExoPlayer (audio focus stays off, so it mixes with game audio in-stream).
 */
@Composable
private fun NanaMusicOnlineTab(
    state: NanaOnlineSearchState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPlaying by NanaMusicPlayer.isPlaying.collectAsState()
    val currentIndex by NanaMusicPlayer.currentIndex.collectAsState()
    val tracks by NanaMusicPlayer.tracks.collectAsState()

    // 1.0.27: search state is hoisted to NanaMusicScreen so it survives tab
    // switches (previously `remember`ed here and reset on every tab change).
    var query by state::query
    var results by state::results
    var searching by state::searching
    var error by state::error
    var playingVideoId by state::playingVideoId

    // Clear the "now playing" highlight when the player moves to a track that
    // isn't this online result (e.g. user picked a local file or pressed next).
    val currentTrack = tracks.getOrNull(currentIndex)
    androidx.compose.runtime.LaunchedEffect(currentTrack) {
        val stillThis = currentTrack?.isOnline == true &&
            results.any { it.videoId == playingVideoId && it.title == currentTrack.title }
        if (!stillThis) playingVideoId = null
    }

    fun doSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            try {
                results = NanaTubeApi.searchSongs(trimmed)
                if (results.isEmpty()) {
                    error = "No results found"
                }
            } catch (e: Exception) {
                results = emptyList()
                error = "Online music temporarily unavailable"
            } finally {
                searching = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search songs, artists…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
        )

        Spacer(Modifier.height(8.dp))

        if (error != null && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = if (error == "Online music temporarily unavailable")
                            Icons.Filled.Warning else Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (error == "Online music temporarily unavailable") {
                        Text(
                            "YouTube may have changed something. Local music still works.",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(results, key = { _, t -> t.videoId }) { _, track ->
                    val isThisPlaying = playingVideoId == track.videoId && isPlaying
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (playingVideoId == track.videoId) return@clickable
                                playingVideoId = track.videoId
                                error = null
                                scope.launch {
                                    val ok = NanaMusicPlayer.playOnlineTrack(context, track)
                                    if (!ok) {
                                        playingVideoId = null
                                        error = "Online music temporarily unavailable"
                                    }
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (playingVideoId == track.videoId) {
                            OpenNowPalette.AccentDefault.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (track.thumbnailUrl != null) {
                                AsyncImage(
                                    model = track.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOf(track.artist, track.durationText)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isThisPlaying) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = "Playing",
                                    tint = OpenNowPalette.AccentDefault,
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                NanaMusicControlBar(
                    title = tracks.getOrNull(currentIndex)?.title ?: "Select a track",
                    isPlaying = isPlaying,
                    hasTracks = tracks.isNotEmpty(),
                    onToggle = { NanaMusicPlayer.togglePlayPause(context) },
                    onNext = { NanaMusicPlayer.next(context) },
                    onPrevious = { NanaMusicPlayer.previous(context) },
                )
            }
        }
    }
}

/**
 * Shared now-playing bar used by the full screen and the in-stream mini player.
 */
@Composable
fun NanaMusicControlBar(
    title: String,
    isPlaying: Boolean,
    hasTracks: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = OpenNowPalette.PanelAlt.copy(alpha = 0.95f),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPrevious, enabled = hasTracks, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onToggle, enabled = hasTracks, modifier = Modifier.size(52.dp)) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = OpenNowPalette.AccentDefault,
                ) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = OpenNowPalette.OnAccent,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onNext, enabled = hasTracks, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }
    }
}
