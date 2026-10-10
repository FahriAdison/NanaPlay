package com.opencloudgaming.opennow
import com.papahchan.nanaplay.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PS5-style "Console Mode" for the Store (Home) and Library pages.
 *
 * Full-bleed artwork of the focused game as the background, an optional genre
 * filter row on top, the focused game's title plus a Play button, and a
 * horizontal strip of game cards along the bottom where the focused card
 * grows. Works in both portrait and landscape; tap selects, tapping the
 * focused card (or Enter / D-pad center) plays it.
 */
@Composable
internal fun ConsoleModeScreen(
    page: AppPage,
    state: OpenNowUiState,
    viewModel: OpenNowViewModel,
    modifier: Modifier = Modifier,
    searchRequested: Boolean = false,
    onSearchDismissed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rawGames = when (page) {
        AppPage.Home -> state.games.ifEmpty { state.catalogResult.games }
        AppPage.Library -> state.libraryGames
        else -> emptyList()
    }
    val searchQuery = when (page) {
        AppPage.Home -> state.catalogSearch
        AppPage.Library -> state.librarySearch
        else -> ""
    }
    val favoriteIds = state.settings.favoriteGameIds

    val orderedGames = remember(rawGames, favoriteIds, page) {
        if (page == AppPage.Library) {
            val fav = favoriteIds.toSet()
            rawGames.sortedWith(compareBy({ it.id !in fav }, { it.title.lowercase() }))
        } else {
            rawGames
        }
    }
    val searchedGames = remember(orderedGames, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) orderedGames
        else orderedGames.filter {
            it.title.lowercase().contains(q) ||
                (it.publisherName?.lowercase()?.contains(q) == true)
        }
    }
    val genres = remember(searchedGames) {
        searchedGames.flatMap { it.genres }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var genreFilter by remember { mutableStateOf<String?>(null) }
    val games = remember(searchedGames, genreFilter) {
        val g = genreFilter
        if (g == null) searchedGames else searchedGames.filter { game -> game.genres.any { it.trim().equals(g, ignoreCase = true) } }
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(games) {
        if (selectedIndex >= games.size) selectedIndex = 0
    }
    val selectedGame = games.getOrNull(selectedIndex)
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, games.size) {
        if (games.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(selectedIndex.coerceIn(0, games.size - 1)) }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) runCatching { focusRequester.requestFocus() }
    }

    // Search is a mode triggered from the nav rail/bar; the field filters the same
    // view-model query the strip already observes.
    val showSearch = searchRequested || searchQuery.isNotBlank()
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequested) {
        if (searchRequested) {
            delay(90)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }
    fun onConsoleSearchQueryChange(next: String) {
        if (page == AppPage.Library) viewModel.setLibrarySearch(next)
        else viewModel.setCatalogSearch(next)
        if (next.isBlank()) onSearchDismissed()
    }

    // NanaPlay 1.0.25: back closes an active search first (clear query + dismiss),
    // instead of exiting the app while the user is still searching.
    BackHandler(enabled = showSearch) {
        onConsoleSearchQueryChange("")
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(OpenNowPalette.Background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || games.isEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp -> {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionRight, Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(games.size - 1)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        games.getOrNull(selectedIndex)?.let { viewModel.play(it) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        val isLandscape = maxWidth > maxHeight

        // Full-bleed background: focused game's banner art, crossfading between games.
        // Kept short (250ms) so browsing feels snappy on mid-range phones.
        Crossfade(
            targetState = selectedGame?.id,
            animationSpec = tween(250),
            modifier = Modifier.fillMaxSize(),
        ) { gameId ->
            val game = games.firstOrNull { it.id == gameId }
            val bgUrl = game?.let(::consoleBackgroundUrl)
            if (bgUrl != null) {
                AsyncImage(
                    model = bgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(OpenNowPalette.Background))
            }
        }
        // Scrims so text stays readable over any artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.62f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )

        // NanaPlay 1.0.25 (fatal fix): the search field must stay visible even
        // when no games match. Previously an empty result replaced the whole
        // column — including the field's clear button — trapping the user in
        // the empty state with no way to clear the query.
        Column(Modifier.fillMaxSize()) {
            if (showSearch) {
                    NativeSearchField(
                        query = searchQuery,
                        onQueryChange = ::onConsoleSearchQueryChange,
                        placeholder = stringResource(R.string.search_games),
                        focusRequester = searchFocusRequester,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = if (isLandscape) 6.dp else 8.dp,
                            ),
                    )
                } else if (genres.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(
                            horizontal = 16.dp,
                            vertical = if (isLandscape) 6.dp else 10.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item {
                            ConsoleGenreChip(label = "All", selected = genreFilter == null) {
                                genreFilter = null
                                selectedIndex = 0
                            }
                        }
                        itemsIndexed(genres) { _, genre ->
                            ConsoleGenreChip(label = genre, selected = genreFilter == genre) {
                                genreFilter = genre
                                selectedIndex = 0
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(if (isLandscape) 6.dp else 10.dp))
                }

            // Empty results (or still loading) render below the search field,
            // so the clear button is always reachable.
            if (games.isEmpty()) {
                ConsoleModeEmptyState(
                    loading = state.loadingGames,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.weight(1f))

                selectedGame?.let { game ->
                    ConsoleSelectedGamePanel(
                        game = game,
                        isFavorite = game.id in favoriteIds,
                        compact = isLandscape,
                        onPlay = { viewModel.play(game) },
                        onToggleFavorite = { viewModel.updateFavorites(game.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }

                Spacer(Modifier.height(if (isLandscape) 8.dp else 14.dp))

                val baseCardWidth = if (isLandscape) 60.dp else 84.dp
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        // Headroom for the focused card's scale pop so it never clips.
                        vertical = if (isLandscape) 12.dp else 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                        val focused = index == selectedIndex
                        // Render-thread scale transform instead of animating width:
                        // no remeasure/relayout of the row, much lighter on GPU/CPU.
                        val cardScale by animateFloatAsState(
                            targetValue = if (focused) 1.25f else 1f,
                            animationSpec = tween(160),
                            label = "consoleCardScale",
                        )
                        val cardUrl = game.tvCardImageUrl?.takeIf { it.isNotBlank() }
                            ?: game.imageUrl?.takeIf { it.isNotBlank() }
                        // Decode a small bitmap sized for the card instead of the
                        // full-res artwork: much cheaper to decode, hold in memory,
                        // and upload to the GPU while flinging the strip. Landscape
                        // cards are tiny (60dp) and many are visible at once, so
                        // use an even smaller thumbnail there.
                        val cardRequest = remember(cardUrl, isLandscape) {
                            cardUrl?.let {
                                ImageRequest.Builder(context)
                                    .data(it)
                                    .size(if (isLandscape) 288 else 480, if (isLandscape) 384 else 640)
                                    .scale(Scale.FILL)
                                    .build()
                            }
                        }
                        Box(
                            Modifier
                                .width(baseCardWidth)
                                .aspectRatio(0.72f)
                                .graphicsLayer {
                                    scaleX = cardScale
                                    scaleY = cardScale
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(OpenNowPalette.ImagePlaceholder)
                                .then(
                                    if (focused) {
                                        Modifier.border(
                                            2.dp,
                                            OpenNowPalette.AccentDefault,
                                            RoundedCornerShape(10.dp),
                                        )
                                    } else Modifier,
                                )
                                .clickable {
                                    if (focused) viewModel.play(game)
                                    else {
                                        selectedIndex = index
                                        scope.launch {
                                            runCatching { listState.animateScrollToItem(index) }
                                        }
                                    }
                                },
                        ) {
                            if (cardRequest != null) {
                                // No alpha on the image itself: alpha < 1 forces an
                                // offscreen render layer per card. Unfocused cards are
                                // dimmed with a cheap translucent overlay instead.
                                Box(Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = cardRequest,
                                        contentDescription = game.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (!focused) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.28f)),
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.SportsEsports,
                                        contentDescription = null,
                                        tint = OpenNowPalette.TextMuted,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(if (isLandscape) 6.dp else 12.dp))
            }
        }
    }
}

private fun consoleBackgroundUrl(game: GameInfo): String? =
    game.tvBannerUrl?.takeIf { it.isNotBlank() }
        ?: game.screenshotUrl?.takeIf { it.isNotBlank() }
        ?: game.imageUrl?.takeIf { it.isNotBlank() }

@Composable
private fun ConsoleGenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OpenNowPalette.AccentDefault,
            selectedLabelColor = OpenNowPalette.OnAccent,
            containerColor = Color.Black.copy(alpha = 0.45f),
            labelColor = OpenNowPalette.TextPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.White.copy(alpha = 0.25f),
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun ConsoleSelectedGamePanel(
    game: GameInfo,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val textShadow = Shadow(color = Color.Black, offset = androidx.compose.ui.geometry.Offset(0f, 3f), blurRadius = 8f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Text(
            game.title,
            color = OpenNowPalette.TextPrimary,
            style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(shadow = textShadow),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            game.publisherName?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(game.genres.take(2).map { it.trim() }.filter { it.isNotBlank() })
        }.joinToString("  •  ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                color = OpenNowPalette.TextPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenNowPalette.AccentDefault,
                    contentColor = OpenNowPalette.OnAccent,
                ),
                contentPadding = if (compact) {
                    PaddingValues(horizontal = 22.dp, vertical = 8.dp)
                } else {
                    PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                },
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_play), fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painter = painterResource(if (isFavorite) R.drawable.ic_save_filled else R.drawable.ic_save),
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) OpenNowPalette.AccentDefault else OpenNowPalette.TextPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun ConsoleModeEmptyState(
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(color = OpenNowPalette.AccentDefault)
                Text(
                    "Loading games…",
                    color = OpenNowPalette.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = OpenNowPalette.TextMuted,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    "No games here yet",
                    color = OpenNowPalette.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Pull down to refresh, or check your connection and linked stores.",
                    color = OpenNowPalette.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
