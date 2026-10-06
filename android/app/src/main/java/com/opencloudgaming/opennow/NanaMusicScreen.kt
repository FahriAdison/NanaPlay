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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette

private fun musicReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * NanaPlay 1.0.25 — standalone music screen (outside a stream).
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
                    "My Music",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Text(
                    "Play your own music from storage",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        // If permission was just granted via the Add button, open the picker.
        // (Handled implicitly: user taps Add again. Keep it simple and explicit.)

        Spacer(Modifier.height(12.dp))

        if (tracks.isEmpty()) {
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
                itemsIndexed(tracks, key = { index, track -> "$index-${track.uri}" }) { index, track ->
                    val selected = index == currentIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { NanaMusicPlayer.play(context, index) },
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
                            Icon(
                                imageVector = if (selected && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (selected) OpenNowPalette.AccentDefault
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                track.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { NanaMusicPlayer.removeTrack(context, index) },
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
