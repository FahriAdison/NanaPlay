package com.opencloudgaming.opennow
import com.papahchan.nanaplay.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 1.0.59 — OpenMultipleDocuments + persistable URI permission.
 *
 * The stock contract's launch intent lacks FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
 * so takePersistableUriPermission() always threw (silently swallowed) and every
 * local track was dropped on the next restore. The flag must be on the LAUNCH
 * intent — adding it afterwards is too late.
 */
private class OpenMultipleDocumentsPersistable : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent {
        return super.createIntent(context, input).apply {
            addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * 1.0.27 — hoisted online-search state. Previously the query/results lived in
 * `remember` inside NanaMusicOnlineTab, so switching tabs destroyed the tab
 * composable and the search reset. Hoisted to NanaMusicScreen (which stays
 * composed across tab switches) so results survive.
 * 1.0.33 — singleton object so the search survives even when NanaMusicScreen
 * itself is destroyed (navigating to Store/Library/Settings and back).
 */
object NanaOnlineSearchState {
    var query by mutableStateOf("")
    var filter by mutableStateOf(NanaMusicSearchFilter.ALL)
    var results by mutableStateOf<List<NanaOnlineTrack>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var playingTrackId by mutableStateOf<String?>(null)
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
    val repeatMode by NanaMusicPlayer.repeatMode.collectAsState()
    val shuffleEnabled by NanaMusicPlayer.shuffleEnabled.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var showNowPlayingSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    val currentTrack = tracks.getOrNull(currentIndex)
    LaunchedEffect(tracks.isEmpty()) {
        if (tracks.isEmpty()) {
            showNowPlayingSheet = false
            showQueueSheet = false
        }
    }
    // 1.0.33: NanaOnlineSearchState is now a singleton — no remember needed,
    // survives navigation to other main tabs.

    val filePicker = rememberLauncherForActivityResult(
        OpenMultipleDocumentsPersistable(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Persist access so tracks survive process restarts. 1.0.59: a
            // failure here is surfaced instead of swallowed — without the
            // grant these tracks won't survive a restart.
            var persistFailed = 0
            uris.forEach { uri ->
                val ok = try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    true
                } catch (e: SecurityException) {
                    false
                } catch (t: Throwable) {
                    false
                }
                if (!ok) persistFailed++
            }
            if (persistFailed > 0) {
                NanaMusicPlayer.setLastError(
                    context.getString(R.string.music_err_persist_permission, persistFailed),
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hasLocalTracks = remember(tracks) { tracks.any { !it.isOnline } }
                    if (hasLocalTracks) {
                        OutlinedButton(
                            onClick = { NanaMusicPlayer.clearQueue(context) },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Clear", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(arrayOf("audio/*"))
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.music_add), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.music_tab_my_music)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.music_tab_online)) },
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
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
                    onPlay = { NanaMusicPlayer.play(context, it) },
                    onRemove = { NanaMusicPlayer.removeTrack(context, it) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NanaMusicOnlineTab(
                    state = NanaOnlineSearchState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            NanaMusicMiniPlayer(
                track = currentTrack ?: tracks.first(),
                isPlaying = isPlaying,
                onToggle = { NanaMusicPlayer.togglePlayPause(context) },
                onNext = { NanaMusicPlayer.next(context) },
                onExpand = { showNowPlayingSheet = true },
                onOpenQueue = { showQueueSheet = true },
            )
        }
    }

    val activeTrack = currentTrack ?: tracks.firstOrNull()
    if (showNowPlayingSheet && activeTrack != null) {
        NanaNowPlayingSheet(
            track = activeTrack,
            tracks = tracks,
            isPlaying = isPlaying,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            onDismiss = { showNowPlayingSheet = false },
            onToggle = { NanaMusicPlayer.togglePlayPause(context) },
            onPrevious = { NanaMusicPlayer.previous(context) },
            onNext = { NanaMusicPlayer.next(context) },
            onToggleRepeat = { NanaMusicPlayer.toggleRepeatMode() },
            onToggleShuffle = { NanaMusicPlayer.toggleShuffle() },
            onOpenQueue = { showQueueSheet = true },
            onOpenSleepTimer = { showSleepTimerSheet = true },
            onOpenMore = { showMoreSheet = true },
        )
    }

    if (showQueueSheet) {
        NanaQueueBottomSheet(
            tracks = tracks,
            currentIndex = currentIndex,
            isPlaying = isPlaying,
            onSelect = { idx -> NanaMusicPlayer.play(context, idx) },
            onRemove = { idx -> NanaMusicPlayer.removeTrack(context, idx) },
            onDismiss = { showQueueSheet = false },
        )
    }

    if (showSleepTimerSheet) {
        NanaSleepTimerBottomSheet(
            onSelectMinutes = { mins ->
                NanaMusicPlayer.setSleepTimer(mins)
                showSleepTimerSheet = false
            },
            onDismiss = { showSleepTimerSheet = false },
        )
    }

    if (showMoreSheet) {
        NanaMusicMoreBottomSheet(
            onDismiss = { showMoreSheet = false },
        )
    }
}

@Composable
private fun NanaMusicLocalTab(
    entries: List<IndexedValue<NanaTrack>>,
    isPlaying: Boolean,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
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
        }
    }
}

/**
 * NanaPlay 1.0.26 — online music tab: search music via NewPipe/JioSaavn and
 * stream it through the shared ExoPlayer.
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
    // 1.0.29: surface playback failures + buffering so "can't play" is diagnosable.
    val playbackError by NanaMusicPlayer.lastError.collectAsState()
    val isBuffering by NanaMusicPlayer.isBuffering.collectAsState()

    // 1.0.27: search state is hoisted to NanaMusicScreen so it survives tab
    // switches (previously `remember`ed here and reset on every tab change).
    var query by state::query
    var filter by state::filter
    var results by state::results
    var searching by state::searching
    var error by state::error
    var playingTrackId by state::playingTrackId
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Clear the "now playing" highlight when the player moves to a track that
    // isn't this online result (e.g. user picked a local file or pressed next).
    val currentTrack = tracks.getOrNull(currentIndex)
    androidx.compose.runtime.LaunchedEffect(currentTrack) {
        val stillThis = currentTrack?.isOnline == true &&
            results.any { it.saavnId == playingTrackId && it.title == currentTrack.title }
        if (!stillThis) playingTrackId = null
    }

    val msgNoResults = stringResource(R.string.music_online_empty)
    val msgUnavailable = stringResource(R.string.music_online_unavailable)

    fun doSearch(q: String, targetFilter: NanaMusicSearchFilter = filter) {
        val trimmed = q.trim()
        if (trimmed.isBlank()) return
        searchJob?.cancel()
        searching = true
        error = null
        val job = scope.launch {
            try {
                results = NanaMusicOnlineSearch.searchSongs(trimmed, filter = targetFilter)
                if (results.isEmpty()) {
                    error = msgNoResults
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                results = emptyList()
                error = msgUnavailable
            } finally {
                searching = false
            }
        }
        searchJob = job
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.music_search_hint)) },
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
            keyboardActions = KeyboardActions(onSearch = { doSearch(query, filter) }),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val filters = listOf(
                NanaMusicSearchFilter.ALL to stringResource(R.string.music_filter_all),
                NanaMusicSearchFilter.SONGS to stringResource(R.string.music_filter_songs),
                NanaMusicSearchFilter.LONG_MIXES to stringResource(R.string.music_filter_long_mixes),
            )
            filters.forEach { (option, label) ->
                val isSelected = filter == option
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (filter != option) {
                            filter = option
                            if (query.isNotBlank()) {
                                doSearch(query, option)
                            }
                        }
                    },
                    label = { Text(label, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OpenNowPalette.AccentDefault,
                        selectedLabelColor = OpenNowPalette.OnAccent,
                        containerColor = OpenNowPalette.PanelAlt,
                        labelColor = OpenNowPalette.TextMuted,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = OpenNowPalette.TextMuted.copy(alpha = 0.28f),
                        selectedBorderColor = OpenNowPalette.AccentDefault,
                    ),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 1.0.29: playback status banner — shows a hard error or buffering so
        // the user can tell "still loading" apart from "failed".
        if (playbackError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "Playback error: $playbackError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (isBuffering) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "Buffering…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (error != null && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = if (error == msgUnavailable)
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
                    if (error == msgUnavailable) {
                        Text(
                            stringResource(R.string.music_online_unavailable_hint),
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
                itemsIndexed(results, key = { idx, t -> "${t.source.name}:${t.saavnId}:$idx" }) { _, track ->
                    val isThisPlaying = playingTrackId == track.saavnId && isPlaying
                    val thumbRequest = remember(track.thumbnailUrl) {
                        track.thumbnailUrl?.let { url ->
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(128, 128)
                                .scale(Scale.FILL)
                                .build()
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (playingTrackId == track.saavnId) return@clickable
                                playingTrackId = track.saavnId
                                error = null
                                scope.launch {
                                    // 1.0.36: only clear the highlight/error for
                                    // THIS tap — the user may have tapped
                                    // another result while this resolve was in
                                    // flight (serialized in the player).
                                    val tappedId = track.saavnId
                                    val ok = NanaMusicPlayer.playOnlineSearchSelection(
                                        context = context,
                                        online = track,
                                        searchResults = results,
                                    )
                                    if (!ok && playingTrackId == tappedId) {
                                        playingTrackId = null
                                        error = msgUnavailable
                                    }
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (playingTrackId == track.saavnId) {
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
                            if (thumbRequest != null) {
                                AsyncImage(
                                    model = thumbRequest,
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
                                    listOfNotNull(
                                        track.artist.takeIf { it.isNotBlank() },
                                        track.playCountText,
                                        track.durationText.takeIf { it.isNotBlank() },
                                    ).joinToString(" • "),
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
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NanaMusicMiniPlayer(
    track: NanaTrack,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = OpenNowPalette.PanelAlt,
        border = BorderStroke(1.dp, OpenNowPalette.AccentDefault.copy(alpha = 0.2f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MiniPlayerProgressArtworkButton(
                thumbnailUrl = track.thumbnailUrl,
                isPlaying = isPlaying,
                onToggle = onToggle,
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = OpenNowPalette.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = track.artist?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            if (track.isOnline) R.string.music_no_artist else R.string.music_now_playing_local,
                        ),
                    color = OpenNowPalette.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.music_next_label),
                    tint = OpenNowPalette.TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.music_queue),
                    tint = OpenNowPalette.AccentDefault,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressArtworkButton(
    thumbnailUrl: String?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val progress by NanaMusicPlayer.progress.collectAsState()
    val fraction = if (progress.durationMs > 0L) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val thumbRequest = remember(thumbnailUrl) {
        thumbnailUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(128, 128)
                .scale(Scale.FILL)
                .build()
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val trackBgColor = OpenNowPalette.TextMuted.copy(alpha = 0.2f)
        val arcColor = OpenNowPalette.AccentDefault
        Canvas(modifier = Modifier.size(44.dp)) {
            val strokePx = 2.5.dp.toPx()
            drawArc(
                color = trackBgColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx),
            )
            if (fraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(OpenNowPalette.Panel),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbRequest != null) {
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f)),
                )
            }
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.music_pause_label else R.string.music_play_label,
                ),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NanaNowPlayingSheet(
    track: NanaTrack,
    tracks: List<NanaTrack>,
    isPlaying: Boolean,
    repeatMode: NanaRepeatMode,
    shuffleEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenMore: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isBuffering by NanaMusicPlayer.isBuffering.collectAsState()
    val sleepTimerRemainingMs by NanaMusicPlayer.sleepTimerRemainingMs.collectAsState()
    val artRequest = remember(track.thumbnailUrl) {
        track.thumbnailUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(512, 512)
                .build()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OpenNowPalette.Background,
        contentColor = OpenNowPalette.TextPrimary,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = stringResource(R.string.music_collapse_label),
                        tint = OpenNowPalette.TextPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.music_now_playing_header),
                        color = OpenNowPalette.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                    )
                    val sourceTitle = stringResource(
                        if (track.isOnline) R.string.music_now_playing_online else R.string.music_now_playing_local,
                    )
                    Text(
                        text = "$sourceTitle • ${stringResource(R.string.music_queue_count, tracks.size)}",
                        color = OpenNowPalette.AccentDefault,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpenMore, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.music_action_more),
                        tint = OpenNowPalette.TextPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 1:1 Matte frame preserving 16:9 and 1:1 artwork without cropping
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black,
                border = BorderStroke(1.dp, OpenNowPalette.PanelAlt),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 310.dp)
                    .aspectRatio(1f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (artRequest != null) {
                        AsyncImage(
                            model = artRequest,
                            contentDescription = track.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(OpenNowPalette.Panel),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = OpenNowPalette.AccentDefault.copy(alpha = 0.6f),
                                modifier = Modifier.size(72.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Title & artist metadata
            Text(
                text = track.title,
                color = OpenNowPalette.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = track.artist?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.music_no_artist),
                    color = OpenNowPalette.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = OpenNowPalette.AccentDefault,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Isolated seekbar leaf composable collecting 500ms progress StateFlow
            NowPlayingSeekSection(
                onSeek = { targetMs -> NanaMusicPlayer.seekTo(targetMs) },
            )

            Spacer(Modifier.height(14.dp))

            // Primary transport controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = OpenNowPalette.PanelAlt,
                    modifier = Modifier.size(56.dp),
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = stringResource(R.string.music_previous_label),
                            tint = OpenNowPalette.TextPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = OpenNowPalette.AccentDefault,
                    modifier = Modifier
                        .height(58.dp)
                        .widthIn(min = 140.dp)
                        .clickable(onClick = onToggle),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.music_pause_label else R.string.music_play_label,
                            ),
                            tint = OpenNowPalette.OnAccent,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (isPlaying) R.string.music_pause_label else R.string.music_play_label,
                            ),
                            color = OpenNowPalette.OnAccent,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Surface(
                    shape = CircleShape,
                    color = OpenNowPalette.PanelAlt,
                    modifier = Modifier.size(56.dp),
                ) {
                    IconButton(onClick = onNext, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.music_next_label),
                            tint = OpenNowPalette.TextPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 5-button utility bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingUtilityButton(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = stringResource(R.string.music_queue),
                    active = false,
                    onClick = onOpenQueue,
                )
                val timerActive = sleepTimerRemainingMs != null
                NowPlayingUtilityButton(
                    icon = Icons.Filled.Timer,
                    label = sleepTimerRemainingMs?.let { formatPlaybackDurationMs(it) }
                        ?: stringResource(R.string.music_sleep_timer),
                    active = timerActive,
                    onClick = onOpenSleepTimer,
                )
                NowPlayingUtilityButton(
                    icon = Icons.Filled.Shuffle,
                    label = stringResource(R.string.music_action_shuffle),
                    active = shuffleEnabled,
                    onClick = onToggleShuffle,
                )
                NowPlayingUtilityButton(
                    icon = if (repeatMode == NanaRepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    label = stringResource(R.string.music_action_repeat),
                    active = repeatMode != NanaRepeatMode.OFF,
                    onClick = onToggleRepeat,
                )
                NowPlayingUtilityButton(
                    icon = Icons.Outlined.Tune,
                    label = stringResource(R.string.music_action_more),
                    active = false,
                    onClick = onOpenMore,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NowPlayingSeekSection(
    onSeek: (Long) -> Unit,
) {
    val progress by NanaMusicPlayer.progress.collectAsState()
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val durationMs = progress.durationMs.coerceAtLeast(0L)
    val effectivePosMs = draggingValue?.toLong() ?: progress.positionMs.coerceIn(0L, maxOf(durationMs, progress.positionMs))
    val sliderMax = durationMs.takeIf { it > 0L }?.toFloat() ?: 1f
    val sliderVal = if (durationMs > 0L) {
        (draggingValue ?: progress.positionMs.toFloat()).coerceIn(0f, sliderMax)
    } else {
        0f
    }

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = sliderVal,
            onValueChange = { draggingValue = it },
            onValueChangeFinished = {
                draggingValue?.let { target -> onSeek(target.toLong()) }
                draggingValue = null
            },
            enabled = durationMs > 0L,
            valueRange = 0f..sliderMax,
            colors = SliderDefaults.colors(
                thumbColor = OpenNowPalette.AccentDefault,
                activeTrackColor = OpenNowPalette.AccentDefault,
                inactiveTrackColor = OpenNowPalette.PanelAlt,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlaybackDurationMs(effectivePosMs),
                color = OpenNowPalette.TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatPlaybackDurationMs(durationMs),
                color = OpenNowPalette.TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun NowPlayingUtilityButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) OpenNowPalette.AccentDefault else OpenNowPalette.TextMuted
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NanaQueueBottomSheet(
    tracks: List<NanaTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OpenNowPalette.Panel,
        contentColor = OpenNowPalette.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.music_queue)} (${tracks.size})",
                    color = OpenNowPalette.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.music_collapse_label),
                        tint = OpenNowPalette.TextMuted,
                    )
                }
            }
            HorizontalDivider(color = OpenNowPalette.PanelAlt)
            Spacer(Modifier.height(8.dp))
            if (tracks.isEmpty()) {
                Text(
                    text = stringResource(R.string.music_queue_empty),
                    color = OpenNowPalette.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(tracks, key = { idx, t -> "${t.saavnId ?: t.uri}:$idx" }) { idx, item ->
                        val isCurrent = idx == currentIndex
                        val thumbRequest = remember(item.thumbnailUrl) {
                            item.thumbnailUrl?.let { url ->
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .size(128, 128)
                                    .scale(Scale.FILL)
                                    .build()
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) {
                                OpenNowPalette.AccentDefault.copy(alpha = 0.16f)
                            } else {
                                OpenNowPalette.PanelAlt.copy(alpha = 0.7f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(idx) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (thumbRequest != null) {
                                    AsyncImage(
                                        model = thumbRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isCurrent && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isCurrent) OpenNowPalette.AccentDefault else OpenNowPalette.TextMuted,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = if (isCurrent) OpenNowPalette.AccentDefault else OpenNowPalette.TextPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.artist?.takeIf { it.isNotBlank() }
                                            ?: stringResource(
                                                if (item.isOnline) R.string.music_now_playing_online else R.string.music_now_playing_local,
                                            ),
                                        color = OpenNowPalette.TextMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { onRemove(idx) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = OpenNowPalette.TextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NanaSleepTimerBottomSheet(
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val remainingMs by NanaMusicPlayer.sleepTimerRemainingMs.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OpenNowPalette.Panel,
        contentColor = OpenNowPalette.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.music_sleep_timer),
                color = OpenNowPalette.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val options = listOf(0, 15, 30, 60)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { mins ->
                    val label = if (mins == 0) {
                        stringResource(R.string.music_sleep_timer_off)
                    } else {
                        stringResource(R.string.music_sleep_timer_minutes, mins)
                    }
                    val selected = if (mins == 0) remainingMs == null else false
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMinutes(mins) },
                        label = { Text(label, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OpenNowPalette.AccentDefault,
                            selectedLabelColor = OpenNowPalette.OnAccent,
                            containerColor = OpenNowPalette.PanelAlt,
                            labelColor = OpenNowPalette.TextPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = OpenNowPalette.TextMuted.copy(alpha = 0.25f),
                            selectedBorderColor = OpenNowPalette.AccentDefault,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NanaMusicMoreBottomSheet(
    onDismiss: () -> Unit,
) {
    val playerVolume by NanaMusicPlayer.playerVolume.collectAsState()
    val autoPlayEnabled by NanaMusicPlayer.autoPlayEnabled.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OpenNowPalette.Panel,
        contentColor = OpenNowPalette.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = OpenNowPalette.AccentDefault,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_volume_label),
                        color = OpenNowPalette.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.music_volume_hint),
                        color = OpenNowPalette.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "${(playerVolume * 100).toInt()}%",
                    color = OpenNowPalette.AccentDefault,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = playerVolume,
                onValueChange = { NanaMusicPlayer.setPlayerVolume(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = OpenNowPalette.AccentDefault,
                    activeTrackColor = OpenNowPalette.AccentDefault,
                    inactiveTrackColor = OpenNowPalette.PanelAlt,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(color = OpenNowPalette.PanelAlt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_autoplay_label),
                        color = OpenNowPalette.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.music_autoplay_desc),
                        color = OpenNowPalette.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoPlayEnabled,
                    onCheckedChange = { NanaMusicPlayer.setAutoPlayEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OpenNowPalette.OnAccent,
                        checkedTrackColor = OpenNowPalette.AccentDefault,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Shared now-playing bar used by the in-stream mini player.
 */
@Composable
fun NanaMusicControlBar(
    title: String,
    isPlaying: Boolean,
    hasTracks: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    repeatMode: NanaRepeatMode = NanaRepeatMode.OFF,
    shuffleEnabled: Boolean = false,
    onToggleRepeat: (() -> Unit)? = null,
    onToggleShuffle: (() -> Unit)? = null,
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
            if (onToggleShuffle != null) {
                IconButton(onClick = onToggleShuffle, enabled = hasTracks, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) OpenNowPalette.AccentDefault else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onPrevious, enabled = hasTracks, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onToggle, enabled = hasTracks, modifier = Modifier.size(48.dp)) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = OpenNowPalette.AccentDefault,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = OpenNowPalette.OnAccent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onNext, enabled = hasTracks, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (onToggleRepeat != null) {
                IconButton(onClick = onToggleRepeat, enabled = hasTracks, modifier = Modifier.size(36.dp)) {
                    val (icon, tint) = when (repeatMode) {
                        NanaRepeatMode.ONE -> Icons.Filled.RepeatOne to OpenNowPalette.AccentDefault
                        NanaRepeatMode.ALL -> Icons.Filled.Repeat to OpenNowPalette.AccentDefault
                        NanaRepeatMode.OFF -> Icons.Filled.Repeat to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Repeat",
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
