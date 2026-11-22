package com.example.ui

import androidx.annotation.OptIn
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.EpgProgram
import com.example.models.MediaItem
import com.example.models.ResolvedStream
import com.example.routing.RoutingManager
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3-Pane TiviMate-Inspired Android TV Live TV Guide:
 * - Left Pane: Selected Live TV Categories (+ All Channels & Favorites)
 * - Middle Pane: Channels list with channel number, logo, title, and live EPG bar
 * - Right Pane: Live Hardware-Accelerated Media3 Preview + Full EPG Timeline
 * Pressing OK on a channel immediately launches Full-Screen Playback.
 */
@Composable
fun LiveTvScreen(
    selectedCategories: List<ContentCategory>,
    allLiveChannels: List<MediaItem>,
    selectedCategoryId: String,
    previewChannel: MediaItem?,
    previewStream: ResolvedStream?,
    isPreviewResolving: Boolean,
    epgByChannelId: Map<String, List<EpgProgram>>,
    onSelectCategory: (String) -> Unit,
    onFocusChannel: (MediaItem) -> Unit,
    onOpenFullScreen: (MediaItem, List<MediaItem>) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onGoToPlaylists: () -> Unit,
    onReopenFilter: () -> Unit
) {
    val liveCategories = remember(selectedCategories) {
        selectedCategories.filter { it.type == ContentType.LIVE }
    }

    val filteredChannels = remember(allLiveChannels, selectedCategoryId) {
        when (selectedCategoryId) {
            "ALL" -> allLiveChannels
            "FAVORITES" -> allLiveChannels.filter { it.isFavorite }
            else -> allLiveChannels.filter { it.categoryId == selectedCategoryId }
        }
    }

    LaunchedEffect(filteredChannels) {
        if (previewChannel == null && filteredChannels.isNotEmpty()) {
            onFocusChannel(filteredChannels.first())
        }
    }

    if (allLiveChannels.isEmpty()) {
        LiveTvEmptyState(
            hasCategories = liveCategories.isNotEmpty(),
            onGoToPlaylists = onGoToPlaylists,
            onReopenFilter = onReopenFilter
        )
        return
    }

    val nowMs = System.currentTimeMillis()
    val activePreview = previewChannel ?: filteredChannels.firstOrNull()
    val activeEpgKey = activePreview?.let { it.epgChannelId.ifBlank { it.id } }.orEmpty()
    val activePrograms = epgByChannelId[activeEpgKey].orEmpty()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // -----------------------------------------------------------------
        // PANE 1 (LEFT ~22%): SELECTED CATEGORIES
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .weight(0.22f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianBase)
                .border(1.dp, DividerSlate.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Groups",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                TvBadge(text = "${liveCategories.size}")
            }
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(3.dp),
                modifier = Modifier.weight(1f)
            ) {
                item(key = "cat_all") {
                    CategoryPaneRow(
                        name = "All Channels",
                        count = allLiveChannels.size,
                        selected = selectedCategoryId == "ALL",
                        onClick = { onSelectCategory("ALL") },
                        testTag = "live_cat_all"
                    )
                }
                item(key = "cat_fav") {
                    CategoryPaneRow(
                        name = "Favorites",
                        count = allLiveChannels.count { it.isFavorite },
                        selected = selectedCategoryId == "FAVORITES",
                        onClick = { onSelectCategory("FAVORITES") },
                        testTag = "live_cat_favorites"
                    )
                }
                items(liveCategories, key = { it.id }) { cat ->
                    val count = allLiveChannels.count { it.categoryId == cat.id }
                    CategoryPaneRow(
                        name = cat.name,
                        subtitle = cat.countryGroup,
                        count = count,
                        selected = selectedCategoryId == cat.id,
                        onClick = { onSelectCategory(cat.id) },
                        testTag = "live_cat_${cat.id}"
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TvActionButton(
                text = "Edit Filter",
                icon = Icons.Default.FilterList,
                onClick = onReopenFilter,
                modifier = Modifier.fillMaxWidth(),
                testTag = "live_edit_filter_button"
            )
        }

        // -----------------------------------------------------------------
        // PANE 2 (MIDDLE ~38%): CHANNELS WITH LOGOS & CURRENT EPG
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianBase)
                .border(1.dp, DividerSlate.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val heading = when (selectedCategoryId) {
                    "ALL" -> "All Live Channels"
                    "FAVORITES" -> "Favorite Channels"
                    else -> liveCategories.firstOrNull { it.id == selectedCategoryId }?.name ?: "Channels"
                }
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TvBadge(text = "${filteredChannels.size} CH")
            }
            Spacer(Modifier.height(6.dp))

            if (filteredChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No channels in this group.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        val epgKey = channel.epgChannelId.ifBlank { channel.id }
                        val programs = epgByChannelId[epgKey].orEmpty()
                        val currentProgram = programs.firstOrNull { it.isCurrentAt(nowMs) } ?: programs.firstOrNull()
                        val isPreviewing = activePreview?.id == channel.id

                        TvFocusableSurface(
                            onClick = { onOpenFullScreen(channel, filteredChannels) },
                            onFocused = { onFocusChannel(channel) },
                            selected = isPreviewing,
                            focusScale = 1.025f,
                            containerColor = MidnightPanel,
                            selectedContainerColor = HighlightSlate,
                            focusedContainerColor = BroadcastCyanDeep.copy(alpha = 0.72f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_channel_${channel.id}")
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Channel Number
                                Text(
                                    text = if (channel.channelNumber > 0) channel.channelNumber.toString().padStart(3, '0') else "---",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isFocused) Color.White else BroadcastCyan,
                                    modifier = Modifier.width(34.dp)
                                )

                                // Channel Logo
                                ChannelLogoThumbnail(
                                    title = channel.title,
                                    logoUrl = channel.posterUrl,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.width(12.dp))

                                // Channel Name + Current EPG Program
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = channel.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (channel.isFavorite) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Favorite",
                                                tint = SignalAmber,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = currentProgram?.title ?: "Live Broadcast • Press OK to watch full screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isFocused) TextPrimary.copy(alpha = 0.9f) else TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (currentProgram != null) {
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { currentProgram.progressAt(nowMs) },
                                            color = BroadcastCyan,
                                            trackColor = ObsidianVoid.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // PANE 3 (RIGHT ~40%): LIVE PREVIEW PLAYER + EPG TIMELINE
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top: 16:9 Live Preview Window
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBase)
                    .border(1.5.dp, DividerSlate, RoundedCornerShape(14.dp))
            ) {
                if (previewStream != null && previewStream.url.isNotBlank()) {
                    LivePreviewVideoPlayer(
                        resolvedStream = previewStream,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_cinema_backdrop_1795334414361),
                        contentDescription = "Live preview backdrop",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Subtle Top & Bottom Gradient Scrim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    ObsidianVoid.copy(alpha = 0.55f),
                                    Color.Transparent,
                                    ObsidianVoid.copy(alpha = 0.88f)
                                )
                            )
                        )
                )

                // Top LIVE status badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LiveCrimson)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("LIVE PREVIEW", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                    if (isPreviewResolving) {
                        CircularProgressIndicator(
                            color = BroadcastCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Bottom channel info + action buttons
                if (activePreview != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activePreview.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Press OK on channel for Full Screen",
                                style = MaterialTheme.typography.bodySmall,
                                color = BroadcastCyan
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvActionButton(
                                text = if (activePreview.isFavorite) "Saved" else "Fav",
                                icon = if (activePreview.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                onClick = { onToggleFavorite(activePreview) },
                                testTag = "live_preview_favorite_button"
                            )
                            TvActionButton(
                                text = "Watch",
                                icon = Icons.Default.PlayArrow,
                                isPrimary = true,
                                onClick = { onOpenFullScreen(activePreview, filteredChannels) },
                                testTag = "live_preview_watch_button"
                            )
                        }
                    }
                }
            }

            // Bottom: EPG Program Timeline
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBase)
                    .border(1.dp, DividerSlate.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "EPG Schedule",
                            tint = BroadcastCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "EPG Program Timeline",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    if (activePreview != null && activePreview.epgChannelId.isNotBlank()) {
                        TvBadge(text = "EPG: ${activePreview.epgChannelId}")
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (activePrograms.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No EPG schedule available for this channel",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Attach an XMLTV EPG URL in Playlists & Filter or connect a Stalker Portal with EPG.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(activePrograms.take(12), key = { "${it.channelId}_${it.startTimeMs}" }) { prog ->
                            val isNow = prog.isCurrentAt(nowMs)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isNow) HighlightSlate else MidnightPanel)
                                    .border(
                                        1.dp,
                                        if (isNow) BroadcastCyan.copy(alpha = 0.6f) else DividerSlate.copy(alpha = 0.4f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${timeFormat.format(Date(prog.startTimeMs))} - ${timeFormat.format(Date(prog.endTimeMs))}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isNow) BroadcastCyan else TextMuted
                                    )
                                    if (isNow) {
                                        TvBadge(
                                            text = "ON AIR",
                                            containerColor = LiveCrimson.copy(alpha = 0.2f),
                                            contentColor = LiveCrimson
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = prog.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (prog.description.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = prog.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isNow) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { prog.progressAt(nowMs) },
                                        color = BroadcastCyan,
                                        trackColor = ObsidianVoid,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
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

@Composable
private fun CategoryPaneRow(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    testTag: String
) {
    TvFocusableSurface(
        onClick = onClick,
        selected = selected,
        focusScale = 1.03f,
        containerColor = MidnightPanel,
        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.45f),
        focusedContainerColor = HighlightSlate,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) { isFocused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused || selected) TextPrimary else TextSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected || isFocused) BroadcastCyan else TextMuted
            )
        }
    }
}

@Composable
fun ChannelLogoThumbnail(
    title: String,
    logoUrl: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedSlate)
            .border(1.dp, DividerSlate, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val initials = remember(title) {
            title.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "TV" }
        }
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            color = BroadcastCyan,
            fontWeight = FontWeight.Bold
        )
        if (logoUrl.isNotBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = "$title logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LivePreviewVideoPlayer(
    resolvedStream: ResolvedStream,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var previewError by remember(resolvedStream.url) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(resolvedStream.url, resolvedStream.headers) {
        val httpClient = RoutingManager.buildHttpClient(customHeaders = resolvedStream.headers)
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                playWhenReady = true
                volume = 0.85f
                setMediaItem(Media3Item.fromUri(resolvedStream.url))
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                previewError = error.errorCodeName
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )
        if (previewError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianVoid.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Preview signal unavailable ($previewError)",
                    style = MaterialTheme.typography.labelMedium,
                    color = SignalAmber
                )
            }
        }
    }
}

@Composable
private fun LiveTvEmptyState(
    hasCategories: Boolean,
    onGoToPlaylists: () -> Unit,
    onReopenFilter: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_cinema_backdrop_1795334414361),
            contentDescription = "Broadcast empty state background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ObsidianVoid.copy(alpha = 0.92f),
                            ObsidianVoid.copy(alpha = 0.72f),
                            ObsidianVoid.copy(alpha = 0.88f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 56.dp)
                .width(620.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.LiveTv, contentDescription = "Live TV", tint = BroadcastCyan, modifier = Modifier.size(34.dp))
                TvBadge(text = "ANDROID TV • 10-FOOT BROADCAST MODE")
            }
            Text(
                text = "Welcome to TiviWave TV",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary
            )
            Text(
                text = "Connect your Stalker Portal (MAG/STB MAC + Serial + Device IDs), Xtream Codes API, M3U/M3U8 URL/File, or GitHub Gist JSON source. Use the 2-Step Country & Category Pre-Load Filter to import only the countries and channels you actually watch.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvActionButton(
                    text = "Add IPTV Playlist",
                    icon = Icons.Default.AddCircle,
                    isPrimary = true,
                    onClick = onGoToPlaylists,
                    testTag = "empty_add_playlist_button"
                )
                if (hasCategories) {
                    TvActionButton(
                        text = "Edit Category Filter",
                        icon = Icons.Default.Tune,
                        onClick = onReopenFilter,
                        testTag = "empty_reopen_filter_button"
                    )
                }
            }
        }
    }
}
