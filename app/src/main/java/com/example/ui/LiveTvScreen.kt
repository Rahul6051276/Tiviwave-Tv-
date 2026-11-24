package com.example.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class GroupDrawerEntry(
    val id: String,
    val name: String,
    val subtitle: String,
    val count: Int
)

/**
 * TiviMate-Style Selected Group Live TV Screen:
 * - Clean #0D1117 / #141A24 broadcast layout without boxy neon borders or cluttered banners.
 * - Top section: Large live video preview window (~40% width) + active channel/program details
 *   (current program title, time range + progress bar, favorite toggle, group switcher).
 * - Bottom section: Full-width Channel + EPG Timeline guide (+30m, +1h, +1.5h, +2h time slots)
 *   showing channel number, logo, channel name, and proportional program blocks.
 * - Group Switcher Drawer opens cleanly on demand (via Group pill or Back/Left) and stays out
 *   of the way while browsing channels.
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
    onReopenFilter: () -> Unit,
    activePlaylistLabel: String = "",
    playlistCount: Int = 0,
    onOpenPlaylistSwitcher: () -> Unit = {}
) {
    val liveCategories = remember(selectedCategories) {
        selectedCategories.filter { it.type == ContentType.LIVE }
    }

    val groupEntries = remember(liveCategories, allLiveChannels) {
        buildList {
            add(
                GroupDrawerEntry(
                    id = "ALL",
                    name = "All Channels",
                    subtitle = "All Selected Groups",
                    count = allLiveChannels.size
                )
            )
            add(
                GroupDrawerEntry(
                    id = "FAVORITES",
                    name = "Favorites",
                    subtitle = "Saved Channels",
                    count = allLiveChannels.count { it.isFavorite }
                )
            )
            liveCategories.forEach { cat ->
                val compositeKey = "${cat.sourceId}::${cat.id}"
                add(
                    GroupDrawerEntry(
                        id = compositeKey,
                        name = cat.name,
                        subtitle = cat.countryGroup,
                        count = allLiveChannels.count { it.sourceId == cat.sourceId && it.categoryId == cat.id }
                    )
                )
            }
        }
    }

    val filteredChannels = remember(allLiveChannels, selectedCategoryId) {
        when (selectedCategoryId) {
            "ALL" -> allLiveChannels
            "FAVORITES" -> allLiveChannels.filter { it.isFavorite }
            else -> allLiveChannels.filter { ch ->
                "${ch.sourceId}::${ch.categoryId}" == selectedCategoryId || ch.categoryId == selectedCategoryId
            }
        }
    }

    val activeGroupName = remember(groupEntries, selectedCategoryId) {
        groupEntries.firstOrNull { it.id == selectedCategoryId }?.name ?: "All Channels"
    }

    if (allLiveChannels.isEmpty()) {
        LiveTvEmptyState(
            hasCategories = liveCategories.isNotEmpty(),
            activePlaylistLabel = activePlaylistLabel,
            playlistCount = playlistCount,
            onGoToPlaylists = onGoToPlaylists,
            onReopenFilter = onReopenFilter,
            onOpenPlaylistSwitcher = onOpenPlaylistSwitcher
        )
        return
    }

    var isGroupDrawerOpen by remember { mutableStateOf(false) }
    val channelListState = rememberLazyListState()
    val groupListState = rememberLazyListState()
    val firstChannelFocusRequester = remember { FocusRequester() }
    val activeGroupFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Ensure a channel is selected for preview when entering or changing groups
    LaunchedEffect(selectedCategoryId, filteredChannels) {
        if (filteredChannels.isNotEmpty() &&
            (previewChannel == null || filteredChannels.none { it.id == previewChannel.id })
        ) {
            onFocusChannel(filteredChannels.first())
        }
    }

    // Predictable D-Pad entry point: focus active/first channel row when entering or after closing group drawer
    LaunchedEffect(selectedCategoryId, isGroupDrawerOpen, filteredChannels.size) {
        if (!isGroupDrawerOpen && filteredChannels.isNotEmpty()) {
            val activeIdx = filteredChannels.indexOfFirst { it.id == previewChannel?.id }.coerceAtLeast(0)
            channelListState.scrollToItem(activeIdx)
            delay(60L)
            runCatching { firstChannelFocusRequester.requestFocus() }
        }
    }

    // Move focus straight to the active group when opening the collapsible Group Switcher Drawer
    LaunchedEffect(isGroupDrawerOpen) {
        if (isGroupDrawerOpen) {
            val idx = groupEntries.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
            groupListState.scrollToItem(idx)
            delay(60L)
            runCatching { activeGroupFocusRequester.requestFocus() }
        }
    }

    BackHandler(enabled = isGroupDrawerOpen) {
        isGroupDrawerOpen = false
    }

    val nowMs = System.currentTimeMillis()
    val slotDurationMs = 30 * 60_000L // 30-minute timeline columns
    // Align timeline anchor to current half-hour boundary
    val timelineAnchorMs = remember(nowMs / 60_000L) {
        (nowMs / slotDurationMs) * slotDurationMs
    }
    val timeSlots = remember(timelineAnchorMs) {
        listOf(
            timelineAnchorMs,
            timelineAnchorMs + slotDurationMs,
            timelineAnchorMs + slotDurationMs * 2,
            timelineAnchorMs + slotDurationMs * 3
        )
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val activePreview = previewChannel ?: filteredChannels.firstOrNull()
    val activeEpgKey = activePreview?.let { it.epgChannelId.ifBlank { it.id } }.orEmpty()
    val activePrograms = epgByChannelId[activeEpgKey].orEmpty()
    val activeNowProgram = activePrograms.firstOrNull { it.isCurrentAt(nowMs) } ?: activePrograms.firstOrNull()
    val activeNextProgram = activePrograms.firstOrNull {
        it.startTimeMs > (activeNowProgram?.startTimeMs ?: nowMs)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // -----------------------------------------------------------------
            // TOP SECTION (~39% HEIGHT): BIG PREVIEW LEFT + PROGRAM DETAILS RIGHT
            // -----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.40f),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Large Live Video Preview (~40% width)
                Box(
                    modifier = Modifier
                        .weight(0.40f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(1.dp, DividerSlate.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
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

                    // Minimal Top-Left LIVE badge & resolving spinner
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(LiveCrimson.copy(alpha = 0.9f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                        if (isPreviewResolving) {
                            CircularProgressIndicator(
                                color = BroadcastCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Right Panel (~60% width): Active Channel & Program Details
                Column(
                    modifier = Modifier
                        .weight(0.60f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBase)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header bar: Group Switcher + Playlist Switcher + Favorite & Filter controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            TvFocusableSurface(
                                onClick = { isGroupDrawerOpen = true },
                                focusScale = 1.03f,
                                shape = RoundedCornerShape(8.dp),
                                containerColor = MidnightPanel,
                                focusedContainerColor = HighlightSlate,
                                borderColor = DividerSlate.copy(alpha = 0.5f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                                modifier = Modifier.testTag("live_group_switcher_button")
                            ) { isFocused ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = "Switch group",
                                        tint = if (isFocused) TextPrimary else BroadcastCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "$activeGroupName (${filteredChannels.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (playlistCount > 1 && activePlaylistLabel.isNotBlank()) {
                                TvFocusableSurface(
                                    onClick = onOpenPlaylistSwitcher,
                                    focusScale = 1.03f,
                                    shape = RoundedCornerShape(8.dp),
                                    containerColor = BroadcastCyanDeep.copy(alpha = 0.28f),
                                    focusedContainerColor = HighlightSlate,
                                    borderColor = BroadcastCyan.copy(alpha = 0.45f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                                    modifier = Modifier.testTag("live_playlist_switcher_button")
                                ) { isFocused ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.SwapHoriz,
                                            contentDescription = "Switch playlist",
                                            tint = if (isFocused) TextPrimary else BroadcastCyan,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = activePlaylistLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (activePreview != null) {
                                TvActionButton(
                                    text = if (activePreview.isFavorite) "Favorite" else "Save",
                                    icon = if (activePreview.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    onClick = { onToggleFavorite(activePreview) },
                                    testTag = "live_preview_favorite_button"
                                )
                                TvActionButton(
                                    text = "Full Screen",
                                    icon = Icons.Default.PlayArrow,
                                    isPrimary = true,
                                    onClick = { onOpenFullScreen(activePreview, filteredChannels) },
                                    testTag = "live_preview_watch_button"
                                )
                            }
                            TvActionButton(
                                text = "",
                                icon = Icons.Default.FilterList,
                                onClick = onReopenFilter,
                                testTag = "live_edit_filter_button"
                            )
                        }
                    }

                    // Active Channel & Current Program Info
                    if (activePreview != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (activePreview.channelNumber > 0) {
                                    Text(
                                        text = activePreview.channelNumber.toString().padStart(3, '0'),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BroadcastCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = activePreview.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = activeNowProgram?.title ?: activePreview.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (activeNowProgram != null) {
                                val remainingMin = ((activeNowProgram.endTimeMs - nowMs) / 60_000L).coerceAtLeast(0L)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = BroadcastCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "${timeFmt.format(Date(activeNowProgram.startTimeMs))} – ${timeFmt.format(Date(activeNowProgram.endTimeMs))}  •  ${remainingMin}m left",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = BroadcastCyan
                                        )
                                    }
                                    if (activeNextProgram != null) {
                                        Text(
                                            text = "Next: ${activeNextProgram.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { activeNowProgram.progressAt(nowMs) },
                                    color = BroadcastCyan,
                                    trackColor = ElevatedSlate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                                if (activeNowProgram.description.isNotBlank()) {
                                    Text(
                                        text = activeNowProgram.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                Text(
                                    text = "$activeGroupName • Live Broadcast Stream",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // BOTTOM SECTION (~60% HEIGHT): WIDE CHANNEL + EPG TIMELINE GUIDE
            // -----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.60f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianBase)
            ) {
                // Timeline Header Row: Channel Column + 4 EPG Time Slots (+30m, +1h, +1.5h, +2h)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MidnightPanel)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(0.30f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = activeGroupName.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = BroadcastCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "◀ Groups",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.weight(0.70f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val slotOffsetLabels = listOf("Now", "+30m", "+1h", "+1.5h")
                        val slotEndLabels = listOf("+30m", "+1h", "+1.5h", "+2h")
                        timeSlots.forEachIndexed { idx, slotStart ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = timeFmt.format(Date(slotStart)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (idx == 0) BroadcastCyan else TextSecondary,
                                    fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = if (idx == 0) slotOffsetLabels[0] else slotEndLabels[idx - 1],
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = DividerSlate.copy(alpha = 0.6f), thickness = 1.dp)

                if (filteredChannels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No channels in $activeGroupName",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    val activeFocusIndex = remember(filteredChannels, activePreview?.id) {
                        filteredChannels.indexOfFirst { it.id == activePreview?.id }.coerceAtLeast(0)
                    }

                    LazyColumn(
                        state = channelListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(filteredChannels, key = { _, ch -> ch.id }) { index, channel ->
                            val epgKey = channel.epgChannelId.ifBlank { channel.id }
                            val programs = epgByChannelId[epgKey].orEmpty()
                            val isSelectedChannel = activePreview?.id == channel.id

                            TvFocusableSurface(
                                onClick = { onOpenFullScreen(channel, filteredChannels) },
                                onFocused = {
                                    onFocusChannel(channel)
                                    scope.launch {
                                        channelListState.animateScrollToItem(index)
                                    }
                                },
                                selected = isSelectedChannel,
                                focusScale = 1.015f,
                                shape = RoundedCornerShape(8.dp),
                                containerColor = ObsidianBase,
                                selectedContainerColor = MidnightPanel,
                                focusedContainerColor = HighlightSlate,
                                borderColor = Color.Transparent,
                                focusedBorderColor = BroadcastCyan,
                                focusedBorderWidth = 2.dp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (index == activeFocusIndex) {
                                            Modifier.focusRequester(firstChannelFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown &&
                                            keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                                        ) {
                                            isGroupDrawerOpen = true
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .testTag("live_channel_${channel.id}")
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left Channel Info Strip (~30% width)
                                    Row(
                                        modifier = Modifier
                                            .weight(0.30f)
                                            .padding(end = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (channel.channelNumber > 0) {
                                                channel.channelNumber.toString().padStart(3, '0')
                                            } else {
                                                (index + 1).toString().padStart(3, '0')
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isFocused) BroadcastCyan else TextMuted,
                                            modifier = Modifier.width(34.dp)
                                        )
                                        ChannelLogoThumbnail(
                                            title = channel.title,
                                            logoUrl = channel.posterUrl,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = channel.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isFocused || isSelectedChannel) TextPrimary else TextSecondary,
                                            fontWeight = if (isFocused || isSelectedChannel) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (channel.isFavorite) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Favorite",
                                                tint = SignalAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    // Right Wide EPG Timeline Blocks (~70% width across 4 half-hour slots)
                                    Row(
                                        modifier = Modifier.weight(0.70f),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        timeSlots.forEachIndexed { slotIdx, slotStart ->
                                            val slotEnd = slotStart + slotDurationMs
                                            val matchingProgram = programs.firstOrNull { prog ->
                                                prog.startTimeMs < slotEnd && prog.endTimeMs > slotStart
                                            }
                                            val isCurrentSlot = slotIdx == 0
                                            val cellBg = when {
                                                isFocused && isCurrentSlot -> BroadcastCyanDeep.copy(alpha = 0.45f)
                                                isFocused -> ElevatedSlate
                                                isCurrentSlot && matchingProgram != null -> MidnightPanel
                                                else -> ObsidianVoid.copy(alpha = 0.65f)
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(42.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(cellBg)
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = matchingProgram?.title ?: if (isCurrentSlot) "Live Broadcast" else "—",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = when {
                                                        matchingProgram != null && (isFocused || isCurrentSlot) -> TextPrimary
                                                        matchingProgram != null -> TextSecondary
                                                        else -> TextMuted.copy(alpha = 0.7f)
                                                    },
                                                    fontWeight = if (isCurrentSlot && matchingProgram != null) FontWeight.Medium else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isCurrentSlot && matchingProgram != null) {
                                                    Spacer(Modifier.height(3.dp))
                                                    LinearProgressIndicator(
                                                        progress = { matchingProgram.progressAt(nowMs) },
                                                        color = BroadcastCyan,
                                                        trackColor = ObsidianVoid.copy(alpha = 0.6f),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(2.5.dp)
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
            }
        }

        // ---------------------------------------------------------------------
        // COLLAPSIBLE GROUP SWITCHER DRAWER (ON-DEMAND OVERLAY)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = isGroupDrawerOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(340.dp)
                        .background(ObsidianBase)
                        .border(1.dp, DividerSlate.copy(alpha = 0.8f))
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Channel Groups",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary
                        )
                        TvBadge(text = "${groupEntries.size}")
                    }
                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        state = groupListState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(groupEntries, key = { _, g -> g.id }) { idx, group ->
                            val isSelected = group.id == selectedCategoryId
                            TvFocusableSurface(
                                onClick = {
                                    onSelectCategory(group.id)
                                    isGroupDrawerOpen = false
                                },
                                onFocused = {
                                    scope.launch { groupListState.animateScrollToItem(idx) }
                                },
                                selected = isSelected,
                                focusScale = 1.02f,
                                shape = RoundedCornerShape(8.dp),
                                containerColor = MidnightPanel,
                                selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.38f),
                                focusedContainerColor = HighlightSlate,
                                focusedBorderColor = BroadcastCyan,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isSelected) Modifier.focusRequester(activeGroupFocusRequester)
                                        else Modifier
                                    )
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown &&
                                            keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                                        ) {
                                            isGroupDrawerOpen = false
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .testTag("live_cat_${group.id}")
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isFocused || isSelected) TextPrimary else TextSecondary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (group.subtitle.isNotBlank()) {
                                            Text(
                                                text = group.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = group.count.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected || isFocused) BroadcastCyan else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    TvActionButton(
                        text = "Edit Category Filter",
                        icon = Icons.Default.FilterList,
                        onClick = {
                            isGroupDrawerOpen = false
                            onReopenFilter()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "drawer_edit_filter_button"
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelLogoThumbnail(
    title: String,
    logoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(ElevatedSlate),
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
            style = MaterialTheme.typography.labelSmall,
            color = BroadcastCyan,
            fontWeight = FontWeight.Bold
        )
        if (logoUrl.isNotBlank()) {
            val logoRequest = remember(logoUrl) {
                coil.request.ImageRequest.Builder(context)
                    .data(logoUrl)
                    .size(96, 96)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = logoRequest,
                contentDescription = "$title logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
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
                    .background(ObsidianVoid.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Preview unavailable ($previewError)",
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
    activePlaylistLabel: String,
    playlistCount: Int,
    onGoToPlaylists: () -> Unit,
    onReopenFilter: () -> Unit,
    onOpenPlaylistSwitcher: () -> Unit
) {
    val addBtnFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { addBtnFocus.requestFocus() }
    }

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
                            ObsidianVoid.copy(alpha = 0.94f),
                            ObsidianVoid.copy(alpha = 0.78f),
                            ObsidianVoid.copy(alpha = 0.90f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 56.dp)
                .width(640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.LiveTv, contentDescription = "Live TV", tint = BroadcastCyan, modifier = Modifier.size(34.dp))
                TvBadge(text = if (playlistCount > 0 && activePlaylistLabel.isNotBlank()) "PLAYLIST: $activePlaylistLabel" else "ANDROID TV • 10-FOOT BROADCAST MODE")
            }
            Text(
                text = if (playlistCount > 1) "No Live Channels in $activePlaylistLabel" else "Welcome to TiviWave TV",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary
            )
            Text(
                text = if (playlistCount > 1) {
                    "Switch to another saved playlist (Xtream / Stalker / M3U) or open the Category Filter to select Live TV groups for this playlist."
                } else {
                    "Connect your Stalker Portal (MAG/STB), Xtream Codes API, M3U/M3U8 URL or File, or GitHub Gist JSON playlist. Use the 2-Step Country & Category Pre-Load Filter to import only the groups you watch."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (playlistCount > 1) {
                    TvActionButton(
                        text = "Switch Playlist",
                        icon = Icons.Default.SwapHoriz,
                        isPrimary = true,
                        onClick = onOpenPlaylistSwitcher,
                        modifier = Modifier.focusRequester(addBtnFocus),
                        testTag = "empty_switch_playlist_button"
                    )
                } else {
                    TvActionButton(
                        text = "Add IPTV Playlist",
                        icon = Icons.Default.AddCircle,
                        isPrimary = true,
                        onClick = onGoToPlaylists,
                        modifier = Modifier.focusRequester(addBtnFocus),
                        testTag = "empty_add_playlist_button"
                    )
                }
                if (hasCategories || playlistCount > 0) {
                    TvActionButton(
                        text = "Edit Category Filter",
                        icon = Icons.Default.Tune,
                        onClick = onReopenFilter,
                        testTag = "empty_reopen_filter_button"
                    )
                }
                if (playlistCount > 1) {
                    TvActionButton(
                        text = "Manage Playlists",
                        icon = Icons.Default.AddCircle,
                        onClick = onGoToPlaylists,
                        testTag = "empty_manage_playlists_button"
                    )
                }
            }
        }
    }
}
