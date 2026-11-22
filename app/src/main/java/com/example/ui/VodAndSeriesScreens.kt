package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.MediaItem
import com.example.models.SeasonGroup
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.OnCyanDark
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TV 5-Column 2:3 Poster Grid for Movies (VOD) and Web Series:
 * - Strictly 5 columns per row (GridCells.Fixed(5)) on Android TV landscape screens.
 * - Readable rating badges & concise titles at 5-column density.
 * - Predictable FocusRequester entry point, auto-scroll on focus, and crisp D-Pad zoom.
 */
@Composable
fun VodOrSeriesGridScreen(
    contentType: ContentType,
    categories: List<ContentCategory>,
    items: List<MediaItem>,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    onSelectItem: (MediaItem) -> Unit,
    onGoToPlaylists: () -> Unit,
    onReopenFilter: () -> Unit
) {
    val typeCategories = remember(categories, contentType) {
        categories.filter { it.type == contentType }
    }
    val typeItems = remember(items, contentType) {
        items.filter { it.type == contentType }
    }
    val filteredItems = remember(typeItems, selectedCategoryId) {
        when (selectedCategoryId) {
            "ALL" -> typeItems
            "FAVORITES" -> typeItems.filter { it.isFavorite }
            "CONTINUE" -> typeItems.filter { it.resumePositionMs > 5_000L }
            else -> typeItems.filter { it.categoryId == selectedCategoryId }
        }
    }

    if (typeItems.isEmpty()) {
        MediaCatalogEmptyState(
            title = if (contentType == ContentType.VOD) "No Movies Imported Yet" else "No Web Series Imported Yet",
            subtitle = "Add a Stalker Portal, Xtream Codes, M3U, or Gist playlist and check your preferred ${contentType.label} categories in the Pre-Load Filter.",
            icon = if (contentType == ContentType.VOD) Icons.Default.Movie else Icons.Default.Tv,
            onGoToPlaylists = onGoToPlaylists,
            onReopenFilter = onReopenFilter
        )
        return
    }

    val gridState = rememberLazyGridState()
    val firstPosterFocus = remember { FocusRequester() }
    val firstPillFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(contentType, selectedCategoryId, filteredItems.isNotEmpty()) {
        delay(70L)
        if (filteredItems.isNotEmpty()) {
            runCatching { firstPosterFocus.requestFocus() }
        } else {
            runCatching { firstPillFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = contentType.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                TvBadge(text = "${filteredItems.size} titles")
            }
            TvActionButton(
                text = "Edit Categories",
                icon = Icons.Default.FilterList,
                onClick = onReopenFilter,
                testTag = "${contentType.name.lowercase()}_edit_filter_button"
            )
        }

        Spacer(Modifier.height(12.dp))

        // Horizontal Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            item(key = "pill_all") {
                CategoryFilterPill(
                    label = "All (${typeItems.size})",
                    selected = selectedCategoryId == "ALL",
                    onClick = { onSelectCategory("ALL") },
                    modifier = Modifier.focusRequester(firstPillFocus)
                )
            }
            val continueCount = typeItems.count { it.resumePositionMs > 5_000L }
            if (continueCount > 0) {
                item(key = "pill_continue") {
                    CategoryFilterPill(
                        label = "Continue ($continueCount)",
                        selected = selectedCategoryId == "CONTINUE",
                        onClick = { onSelectCategory("CONTINUE") }
                    )
                }
            }
            item(key = "pill_fav") {
                CategoryFilterPill(
                    label = "Favorites (${typeItems.count { it.isFavorite }})",
                    selected = selectedCategoryId == "FAVORITES",
                    onClick = { onSelectCategory("FAVORITES") }
                )
            }
            items(typeCategories, key = { it.id }) { cat ->
                CategoryFilterPill(
                    label = "${cat.name} (${typeItems.count { it.categoryId == cat.id }})",
                    selected = selectedCategoryId == cat.id,
                    onClick = { onSelectCategory(cat.id) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Strict 5-Column 2:3 Poster Grid for 10-Foot Android TV
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("${contentType.name.lowercase()}_poster_grid")
        ) {
            itemsIndexed(filteredItems, key = { _, item -> item.id }) { index, item ->
                TvPosterCard(
                    item = item,
                    onClick = { onSelectItem(item) },
                    onFocused = {
                        scope.launch {
                            gridState.animateScrollToItem(index)
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstPosterFocus) else Modifier,
                    testTag = "poster_${item.id}"
                )
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    allItems: List<MediaItem>,
    onOpenLiveChannel: (MediaItem, List<MediaItem>) -> Unit,
    onOpenVodOrSeries: (MediaItem) -> Unit
) {
    val favoriteItems = remember(allItems) { allItems.filter { it.isFavorite } }
    val favLive = remember(favoriteItems) { favoriteItems.filter { it.type == ContentType.LIVE } }
    val favVodAndSeries = remember(favoriteItems) { favoriteItems.filter { it.type != ContentType.LIVE } }
    val firstFavFocus = remember { FocusRequester() }

    LaunchedEffect(favoriteItems.isNotEmpty()) {
        if (favoriteItems.isNotEmpty()) {
            delay(70L)
            runCatching { firstFavFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Favorites",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            TvBadge(text = "${favoriteItems.size} saved")
        }
        Spacer(Modifier.height(16.dp))

        if (favoriteItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBase),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.StarBorder, contentDescription = "No favorites", tint = SignalAmber, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No Favorites Saved Yet", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Star any Live TV channel, Movie, or Web Series for instant one-click access here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (favLive.isNotEmpty()) {
                    item(key = "fav_live_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Favorite Live Channels", style = MaterialTheme.typography.titleLarge, color = BroadcastCyan)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                itemsIndexed(favLive, key = { _, ch -> ch.id }) { idx, ch ->
                                    TvFocusableSurface(
                                        onClick = { onOpenLiveChannel(ch, favLive) },
                                        focusScale = 1.04f,
                                        containerColor = ObsidianBase,
                                        focusedContainerColor = HighlightSlate,
                                        modifier = Modifier
                                            .width(220.dp)
                                            .then(if (idx == 0) Modifier.focusRequester(firstFavFocus) else Modifier)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ChannelLogoThumbnail(
                                                title = ch.title,
                                                logoUrl = ch.posterUrl,
                                                modifier = Modifier.size(44.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = ch.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text("Live TV", style = MaterialTheme.typography.labelSmall, color = BroadcastCyan)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (favVodAndSeries.isNotEmpty()) {
                    item(key = "fav_vod_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Favorite Movies & Web Series", style = MaterialTheme.typography.titleLarge, color = BroadcastCyan)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(6.dp)
                            ) {
                                itemsIndexed(favVodAndSeries, key = { _, it -> it.id }) { idx, item ->
                                    TvPosterCard(
                                        item = item,
                                        onClick = { onOpenVodOrSeries(item) },
                                        modifier = Modifier
                                            .width(156.dp)
                                            .then(
                                                if (favLive.isEmpty() && idx == 0) Modifier.focusRequester(firstFavFocus)
                                                else Modifier
                                            ),
                                        testTag = "fav_poster_${item.id}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full 16:9 TV Details Screen for Movies and Web Series (Season tabs + Episode list + Resume).
 */
@Composable
fun MediaDetailScreen(
    item: MediaItem,
    seriesSeasons: List<SeasonGroup>,
    isLoadingSeasons: Boolean,
    onPlayItem: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var selectedSeasonIndex by remember(item.id, seriesSeasons.size) { mutableIntStateOf(0) }
    val primaryActionFocus = remember { FocusRequester() }

    LaunchedEffect(seriesSeasons.size) {
        if (selectedSeasonIndex >= seriesSeasons.size) selectedSeasonIndex = 0
    }

    LaunchedEffect(item.id, isLoadingSeasons) {
        delay(80L)
        runCatching { primaryActionFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        if (item.backdropUrl.isNotBlank() || item.posterUrl.isNotBlank()) {
            AsyncImage(
                model = item.backdropUrl.ifBlank { item.posterUrl },
                contentDescription = "${item.title} backdrop",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.img_cinema_backdrop_1795334414361),
                contentDescription = "Cinema backdrop",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ObsidianVoid.copy(alpha = 0.96f),
                            ObsidianVoid.copy(alpha = 0.88f),
                            ObsidianVoid.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TvActionButton(
                    text = "Back",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    testTag = "detail_back_button"
                )
                TvBadge(text = item.type.label.uppercase())
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Column(
                    modifier = Modifier.width(205.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElevatedSlate)
                            .border(1.dp, DividerSlate, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.type == ContentType.VOD) Icons.Default.Movie else Icons.Default.Tv,
                            contentDescription = item.title,
                            tint = BroadcastCyan.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        if (item.posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = item.posterUrl,
                                contentDescription = "${item.title} poster",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    TvActionButton(
                        text = if (item.isFavorite) "In Favorites" else "Add Favorite",
                        icon = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        onClick = { onToggleFavorite(item) },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "detail_favorite_button"
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.year.isNotBlank()) TvBadge(text = item.year)
                        if (item.rating.isNotBlank()) {
                            TvBadge(
                                text = "★ ${item.rating}",
                                containerColor = SignalAmber.copy(alpha = 0.2f),
                                contentColor = SignalAmber
                            )
                        }
                        if (item.resumePositionMs > 5_000L) {
                            TvBadge(
                                text = "Resume ${formatDurationMs(item.resumePositionMs)}",
                                containerColor = BroadcastCyan.copy(alpha = 0.18f),
                                contentColor = BroadcastCyan
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = item.plot.ifBlank { "Hardware-accelerated Media3 TV playback ready." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(18.dp))

                    if (item.type == ContentType.VOD) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TvActionButton(
                                text = if (item.resumePositionMs > 5_000L) {
                                    "Resume (${formatDurationMs(item.resumePositionMs)})"
                                } else {
                                    "Play Movie"
                                },
                                icon = Icons.Default.PlayArrow,
                                isPrimary = true,
                                onClick = { onPlayItem(item) },
                                modifier = Modifier.focusRequester(primaryActionFocus),
                                testTag = "detail_play_movie_button"
                            )
                            if (item.resumePositionMs > 5_000L) {
                                TvActionButton(
                                    text = "Start From Beginning",
                                    icon = Icons.Default.Replay,
                                    onClick = { onPlayItem(item.copy(resumePositionMs = 0L)) },
                                    testTag = "detail_restart_movie_button"
                                )
                            }
                        }
                    } else {
                        if (isLoadingSeasons) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                CircularProgressIndicator(color = BroadcastCyan, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                                Text("Loading seasons and episodes...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                            }
                        } else if (seriesSeasons.isEmpty()) {
                            if (item.streamUrlOrCmd.isNotBlank()) {
                                TvActionButton(
                                    text = "Play Episode",
                                    icon = Icons.Default.PlayArrow,
                                    isPrimary = true,
                                    onClick = { onPlayItem(item) },
                                    modifier = Modifier.focusRequester(primaryActionFocus),
                                    testTag = "detail_play_direct_episode"
                                )
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(seriesSeasons.size) { index ->
                                    val season = seriesSeasons[index]
                                    CategoryFilterPill(
                                        label = "${season.title} (${season.episodes.size})",
                                        selected = selectedSeasonIndex == index,
                                        onClick = { selectedSeasonIndex = index }
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            val activeSeason = seriesSeasons.getOrNull(selectedSeasonIndex)
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                itemsIndexed(activeSeason?.episodes.orEmpty(), key = { _, ep -> ep.id }) { idx, ep ->
                                    TvFocusableSurface(
                                        onClick = { onPlayItem(ep) },
                                        focusScale = 1.02f,
                                        containerColor = ObsidianBase.copy(alpha = 0.9f),
                                        focusedContainerColor = HighlightSlate,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (idx == 0) Modifier.focusRequester(primaryActionFocus) else Modifier)
                                            .testTag("episode_${ep.id}")
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isFocused) BroadcastCyanDeep else ElevatedSlate)
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = "E${ep.episodeNumber.toString().padStart(2, '0')}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isFocused) Color.White else BroadcastCyan,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = ep.title,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (ep.resumePositionMs > 5_000L) {
                                                    Text(
                                                        text = "Resume from ${formatDurationMs(ep.resumePositionMs)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = BroadcastCyan
                                                    )
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play ${ep.title}",
                                                tint = if (isFocused) Color.White else BroadcastCyan,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    testTag: String = ""
) {
    TvFocusableSurface(
        onClick = onClick,
        onFocused = onFocused,
        focusScale = 1.06f,
        shape = RoundedCornerShape(10.dp),
        containerColor = ObsidianBase,
        focusedContainerColor = HighlightSlate,
        borderColor = Color.Transparent,
        focusedBorderColor = BroadcastCyan,
        focusedBorderWidth = 2.dp,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
    ) { isFocused ->
        Column(modifier = Modifier.fillMaxWidth()) {
            // Strict 2:3 Poster Frame tuned for 5-column TV grid readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MidnightPanel)
            ) {
                Icon(
                    imageVector = if (item.type == ContentType.VOD) Icons.Default.Movie else Icons.Default.Tv,
                    contentDescription = item.title,
                    tint = TextMuted.copy(alpha = 0.35f),
                    modifier = Modifier
                        .size(38.dp)
                        .align(Alignment.Center)
                )
                if (item.posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = "${item.title} poster",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Compact high-contrast rating & favorite overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.rating.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(ObsidianVoid.copy(alpha = 0.86f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "★ ${item.rating.take(4)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = SignalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (item.resumePositionMs > 5_000L && item.durationMs > 0L) {
                    val progress = (item.resumePositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.05f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        color = BroadcastCyan,
                        trackColor = ObsidianVoid.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) TextPrimary else TextSecondary,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val metaLine = buildList {
                    if (item.year.isNotBlank()) add(item.year)
                    if (item.resumePositionMs > 5_000L) add(formatDurationMs(item.resumePositionMs))
                }.joinToString(" • ").ifBlank { item.type.label }
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) BroadcastCyan else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CategoryFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvFocusableSurface(
        onClick = onClick,
        selected = selected,
        focusScale = 1.04f,
        shape = RoundedCornerShape(18.dp),
        containerColor = ObsidianBase,
        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.50f),
        focusedContainerColor = BroadcastCyan,
        borderColor = DividerSlate.copy(alpha = 0.45f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier
    ) { isFocused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) OnCyanDark else if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun MediaCatalogEmptyState(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onGoToPlaylists: () -> Unit,
    onReopenFilter: () -> Unit
) {
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { addFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianBase)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = title, tint = BroadcastCyan, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = "Add Playlist",
                    icon = Icons.Default.AddCircle,
                    isPrimary = true,
                    onClick = onGoToPlaylists,
                    modifier = Modifier.focusRequester(addFocus)
                )
                TvActionButton(
                    text = "Edit Category Filter",
                    icon = Icons.Default.FilterList,
                    onClick = onReopenFilter
                )
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
