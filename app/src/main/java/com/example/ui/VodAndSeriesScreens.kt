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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.util.Locale

/**
 * TV 2:3 Poster Grid for Movies (VOD) and Web Series with D-Pad Focus Zoom,
 * Selected Category Filter Pills, Resume Progress Indicators, and Details View.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = contentType.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "${filteredItems.size} titles • Showing selected categories only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            TvActionButton(
                text = "Edit Categories",
                icon = Icons.Default.FilterList,
                onClick = onReopenFilter,
                testTag = "${contentType.name.lowercase()}_edit_filter_button"
            )
        }

        Spacer(Modifier.height(14.dp))

        // Horizontal Category Filter Strip
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            item(key = "pill_all") {
                CategoryFilterPill(
                    label = "All (${typeItems.size})",
                    selected = selectedCategoryId == "ALL",
                    onClick = { onSelectCategory("ALL") }
                )
            }
            val continueCount = typeItems.count { it.resumePositionMs > 5_000L }
            if (continueCount > 0) {
                item(key = "pill_continue") {
                    CategoryFilterPill(
                        label = "Continue Watching ($continueCount)",
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

        Spacer(Modifier.height(16.dp))

        // 2:3 Poster Grid with D-Pad Focus Zoom
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 154.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredItems, key = { it.id }) { item ->
                TvPosterCard(
                    item = item,
                    onClick = { onSelectItem(item) },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )
        Text(
            text = "${favoriteItems.size} saved channels, movies, and series",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
        Spacer(Modifier.height(16.dp))

        if (favoriteItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianBase)
                    .border(1.dp, DividerSlate, RoundedCornerShape(16.dp)),
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
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                items(favLive, key = { it.id }) { ch ->
                                    TvFocusableSurface(
                                        onClick = { onOpenLiveChannel(ch, favLive) },
                                        focusScale = 1.05f,
                                        modifier = Modifier.width(230.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ChannelLogoThumbnail(
                                                title = ch.title,
                                                logoUrl = ch.posterUrl,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = ch.title,
                                                    style = MaterialTheme.typography.titleMedium,
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
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(6.dp)
                            ) {
                                items(favVodAndSeries, key = { it.id }) { item ->
                                    TvPosterCard(
                                        item = item,
                                        onClick = { onOpenVodOrSeries(item) },
                                        modifier = Modifier.width(164.dp),
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
 * Full 16:9 TV Details Screen for Movies and Web Series (including Season tabs & Episode list + Resume Playback).
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
    LaunchedEffect(seriesSeasons.size) {
        if (selectedSeasonIndex >= seriesSeasons.size) selectedSeasonIndex = 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Backdrop Artwork
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

        // High-Contrast Cinematic Gradient Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ObsidianVoid.copy(alpha = 0.96f),
                            ObsidianVoid.copy(alpha = 0.86f),
                            ObsidianVoid.copy(alpha = 0.70f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 22.dp)
        ) {
            // Top Bar
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
                // Left Poster (2:3) + Quick Meta
                Column(
                    modifier = Modifier.width(210.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ElevatedSlate)
                            .border(2.dp, DividerSlate, RoundedCornerShape(14.dp)),
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

                // Right Column: Title, Plot, Play/Resume OR Web Series Season/Episodes
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
                                text = "IMDb ${item.rating}",
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
                        text = item.plot.ifBlank { "Hardware-accelerated Media3 TV playback ready. Press OK on Play to start watching." },
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
                        // Web Series: Season Selector + Episode List
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Direct episode stream ready.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                                if (item.streamUrlOrCmd.isNotBlank()) {
                                    TvActionButton(
                                        text = "Play Episode",
                                        icon = Icons.Default.PlayArrow,
                                        isPrimary = true,
                                        onClick = { onPlayItem(item) },
                                        testTag = "detail_play_direct_episode"
                                    )
                                }
                            }
                        } else {
                            // Season Tabs
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                items(activeSeason?.episodes.orEmpty(), key = { it.id }) { ep ->
                                    TvFocusableSurface(
                                        onClick = { onPlayItem(ep) },
                                        focusScale = 1.02f,
                                        containerColor = MidnightPanel.copy(alpha = 0.9f),
                                        focusedContainerColor = BroadcastCyanDeep,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("episode_${ep.id}")
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isFocused) Color.White.copy(alpha = 0.2f) else ElevatedSlate)
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
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
                                                modifier = Modifier.size(24.dp)
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
    testTag: String = ""
) {
    TvFocusableSurface(
        onClick = onClick,
        focusScale = 1.06f,
        shape = RoundedCornerShape(12.dp),
        containerColor = MidnightPanel,
        focusedContainerColor = HighlightSlate,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
    ) { isFocused ->
        Column(modifier = Modifier.fillMaxWidth()) {
            // Strict 2:3 Poster Frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(ElevatedSlate)
            ) {
                Icon(
                    imageVector = if (item.type == ContentType.VOD) Icons.Default.Movie else Icons.Default.Tv,
                    contentDescription = item.title,
                    tint = TextMuted.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(44.dp)
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

                // Rating / Year Top Badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (item.rating.isNotBlank()) {
                        TvBadge(
                            text = "★ ${item.rating}",
                            containerColor = ObsidianVoid.copy(alpha = 0.85f),
                            contentColor = SignalAmber
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = SignalAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Resume Progress Bar at Bottom of Poster
                if (item.resumePositionMs > 5_000L && item.durationMs > 0L) {
                    val progress = (item.resumePositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.05f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        color = BroadcastCyan,
                        trackColor = ObsidianVoid.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) TextPrimary else TextSecondary,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val metaLine = buildList {
                    if (item.year.isNotBlank()) add(item.year)
                    if (item.resumePositionMs > 5_000L) add("Resume ${formatDurationMs(item.resumePositionMs)}")
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
    onClick: () -> Unit
) {
    TvFocusableSurface(
        onClick = onClick,
        selected = selected,
        focusScale = 1.04f,
        shape = RoundedCornerShape(20.dp),
        containerColor = MidnightPanel,
        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.55f),
        focusedContainerColor = BroadcastCyan,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianBase)
                .border(1.dp, DividerSlate, RoundedCornerShape(16.dp))
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
                    onClick = onGoToPlaylists
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
