package com.example.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.models.ContentType
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.ProxyProtocol
import com.example.models.RoutingNode
import com.example.models.RoutingState
import com.example.models.SourceType
import com.example.models.TvSection
import com.example.routing.RoutingManager
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.ConnectedEmerald
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.OnCyanDark
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.IptvUiState
import com.example.viewmodel.IptvViewModel

/**
 * Root Android TV Shell with Collapsible Left Navigation Rail:
 * - Live TV | Movies | Web Series | Favorites | Country/Proxy Route | Playlists & Filter
 * - Automatically expands on D-Pad focus into the rail, collapses to 76dp icon rail when browsing content.
 * - Full-screen switches cleanly into CategoryFilterScreen, MediaDetailScreen, or TvPlayerScreen.
 */
@Composable
fun HomeTvScreen(
    uiState: IptvUiState,
    viewModel: IptvViewModel
) {
    // Full-screen overrides (Player > Category Pre-Load Filter > Media Details > Main TV Shell)
    val activePlayerItem = uiState.playerItem
    if (activePlayerItem != null) {
        TvPlayerScreen(
            item = activePlayerItem,
            resolvedStream = uiState.playerStream,
            isResolvingStream = uiState.isResolvingPlayerStream,
            resolveError = uiState.playerError,
            liveZapQueue = uiState.liveZapQueue,
            epgByChannelId = uiState.epgByChannelId,
            onZapChannel = viewModel::zapLiveChannel,
            onSwitchLiveChannel = { ch -> viewModel.openFullScreenPlayer(ch, uiState.liveZapQueue) },
            onRefreshStream = viewModel::refreshActivePlayerStream,
            onToggleFavorite = viewModel::toggleFavorite,
            onSaveResumePosition = viewModel::saveResumePosition,
            onExitPlayer = viewModel::closeFullScreenPlayer
        )
        return
    }

    if (uiState.isFilterScreenOpen && uiState.filterSource != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            CategoryFilterScreen(
                source = uiState.filterSource,
                categories = uiState.filterDraftCategories,
                onToggleCategory = viewModel::toggleDraftCategory,
                onSelectCountry = viewModel::setDraftCountrySelection,
                onSelectTabAll = viewModel::setDraftTabSelection,
                onImportSelected = viewModel::confirmAndImportSelectedCategories,
                onCancel = viewModel::closeCategoryFilter
            )
            GlobalBusyAndStatusOverlay(
                isBusy = uiState.isBusy,
                busyMessage = uiState.busyMessage,
                bannerMessage = uiState.bannerMessage,
                errorMessage = uiState.errorMessage,
                onDismissBanner = viewModel::dismissBanner
            )
        }
        return
    }

    val detailItem = uiState.detailItem
    if (detailItem != null) {
        MediaDetailScreen(
            item = detailItem,
            seriesSeasons = uiState.seriesSeasons,
            isLoadingSeasons = uiState.isLoadingSeasons,
            onPlayItem = { playable -> viewModel.openFullScreenPlayer(playable) },
            onToggleFavorite = viewModel::toggleFavorite,
            onBack = viewModel::closeMediaDetails
        )
        return
    }

    var isRailExpanded by remember { mutableStateOf(false) }
    var showPlaylistSwitcher by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (isRailExpanded) 244.dp else 78.dp,
        label = "tv_rail_width"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // -----------------------------------------------------------------
            // COLLAPSIBLE TV LEFT NAVIGATION RAIL
            // -----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .background(ObsidianBase)
                    .border(width = 1.dp, color = DividerSlate.copy(alpha = 0.65f))
                    .onFocusChanged { isRailExpanded = it.hasFocus }
                    .padding(horizontal = 10.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Brand Mark
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BroadcastCyanDeep),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = "TiviWave TV",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        if (isRailExpanded) {
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "TiviWave TV",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = "ANDROID TV IPTV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BroadcastCyan,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    val navItems = listOf(
                        Triple(TvSection.LIVE_TV, Icons.Default.LiveTv, "Live TV"),
                        Triple(TvSection.MOVIES, Icons.Default.Movie, "Movies"),
                        Triple(TvSection.WEB_SERIES, Icons.Default.Tv, "Web Series"),
                        Triple(TvSection.FAVORITES, Icons.Default.Star, "Favorites"),
                        Triple(TvSection.ROUTING, Icons.Default.Public, "Country / Proxy"),
                        Triple(TvSection.PLAYLISTS, Icons.Default.FilterList, "Playlists & Filter")
                    )

                    navItems.forEach { (section, icon, label) ->
                        val isSelected = uiState.currentSection == section
                        TvFocusableSurface(
                            onClick = { viewModel.selectSection(section) },
                            selected = isSelected,
                            focusScale = 1.03f,
                            shape = RoundedCornerShape(12.dp),
                            containerColor = Color.Transparent,
                            selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.4f),
                            focusedContainerColor = BroadcastCyan,
                            borderColor = Color.Transparent,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nav_rail_${section.name.lowercase()}")
                        ) { isFocused ->
                            val tint = when {
                                isFocused -> OnCyanDark
                                isSelected -> BroadcastCyan
                                else -> TextSecondary
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = tint,
                                    modifier = Modifier.size(24.dp)
                                )
                                if (isRailExpanded) {
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isFocused) OnCyanDark else if (isSelected) TextPrimary else TextSecondary,
                                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Quick Playlist Switcher + Routing Status Indicator on Rail
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.playlists.size > 1) {
                        TvFocusableSurface(
                            onClick = { showPlaylistSwitcher = true },
                            selected = false,
                            focusScale = 1.03f,
                            shape = RoundedCornerShape(10.dp),
                            containerColor = BroadcastCyanDeep.copy(alpha = 0.25f),
                            focusedContainerColor = BroadcastCyan,
                            borderColor = BroadcastCyan.copy(alpha = 0.45f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nav_rail_switch_playlist")
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isRailExpanded) Arrangement.Start else Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "Switch Playlist",
                                    tint = if (isFocused) OnCyanDark else BroadcastCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (isRailExpanded) {
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = uiState.activePlaylistLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (isFocused) OnCyanDark else TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Switch Playlist (${uiState.playlists.size})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isFocused) OnCyanDark.copy(alpha = 0.85f) else BroadcastCyan,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (uiState.routingState.isConnected) ConnectedEmerald.copy(alpha = 0.16f) else MidnightPanel)
                            .border(
                                1.dp,
                                if (uiState.routingState.isConnected) ConnectedEmerald.copy(alpha = 0.5f) else DividerSlate.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isRailExpanded) Arrangement.Start else Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (uiState.routingState.isConnected) ConnectedEmerald else TextMuted)
                        )
                        if (isRailExpanded) {
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (uiState.routingState.isConnected) {
                                        uiState.routingState.activeNode?.countryName ?: "Proxy Active"
                                    } else {
                                        "Direct Route"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (uiState.routingState.isConnected) ConnectedEmerald else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (uiState.routingState.isConnected) "VPN / Proxy On" else "No Proxy",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // MAIN CONTENT PANE
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val activeSourceForFilter = uiState.activePlaylist ?: uiState.playlists.firstOrNull()
                val visibleCategories = uiState.visibleSelectedCategories
                val visibleItems = uiState.visibleMediaItems

                when (uiState.currentSection) {
                    TvSection.LIVE_TV -> LiveTvScreen(
                        selectedCategories = visibleCategories,
                        allLiveChannels = visibleItems.filter { it.type == ContentType.LIVE },
                        selectedCategoryId = uiState.selectedLiveCategoryId,
                        previewChannel = uiState.previewChannel,
                        previewStream = uiState.previewStream,
                        isPreviewResolving = uiState.isPreviewResolving,
                        epgByChannelId = uiState.epgByChannelId,
                        onSelectCategory = viewModel::selectLiveCategory,
                        onFocusChannel = { ch -> viewModel.focusOrPreviewChannel(ch, startStreamPreview = true) },
                        onOpenFullScreen = { ch, queue -> viewModel.openFullScreenPlayer(ch, queue) },
                        onToggleFavorite = viewModel::toggleFavorite,
                        onGoToPlaylists = { viewModel.selectSection(TvSection.PLAYLISTS) },
                        onReopenFilter = {
                            if (activeSourceForFilter != null) {
                                viewModel.openCategoryFilterForExistingPlaylist(activeSourceForFilter)
                            } else {
                                viewModel.selectSection(TvSection.PLAYLISTS)
                            }
                        },
                        activePlaylistLabel = uiState.activePlaylistLabel,
                        playlistCount = uiState.playlists.size,
                        onOpenPlaylistSwitcher = { showPlaylistSwitcher = true }
                    )

                    TvSection.MOVIES -> VodOrSeriesGridScreen(
                        contentType = ContentType.VOD,
                        categories = visibleCategories,
                        items = visibleItems,
                        selectedCategoryId = uiState.selectedVodCategoryId,
                        onSelectCategory = viewModel::selectVodCategory,
                        onSelectItem = viewModel::openMediaDetails,
                        onGoToPlaylists = { viewModel.selectSection(TvSection.PLAYLISTS) },
                        onReopenFilter = {
                            if (activeSourceForFilter != null) viewModel.openCategoryFilterForExistingPlaylist(activeSourceForFilter)
                            else viewModel.selectSection(TvSection.PLAYLISTS)
                        },
                        activePlaylistLabel = uiState.activePlaylistLabel,
                        playlistCount = uiState.playlists.size,
                        onOpenPlaylistSwitcher = { showPlaylistSwitcher = true }
                    )

                    TvSection.WEB_SERIES -> VodOrSeriesGridScreen(
                        contentType = ContentType.SERIES,
                        categories = visibleCategories,
                        items = visibleItems,
                        selectedCategoryId = uiState.selectedSeriesCategoryId,
                        onSelectCategory = viewModel::selectSeriesCategory,
                        onSelectItem = viewModel::openMediaDetails,
                        onGoToPlaylists = { viewModel.selectSection(TvSection.PLAYLISTS) },
                        onReopenFilter = {
                            if (activeSourceForFilter != null) viewModel.openCategoryFilterForExistingPlaylist(activeSourceForFilter)
                            else viewModel.selectSection(TvSection.PLAYLISTS)
                        },
                        activePlaylistLabel = uiState.activePlaylistLabel,
                        playlistCount = uiState.playlists.size,
                        onOpenPlaylistSwitcher = { showPlaylistSwitcher = true }
                    )

                    TvSection.FAVORITES -> FavoritesScreen(
                        allItems = visibleItems,
                        onOpenLiveChannel = { ch, queue -> viewModel.openFullScreenPlayer(ch, queue) },
                        onOpenVodOrSeries = viewModel::openMediaDetails
                    )

                    TvSection.ROUTING -> CountryProxyRoutingScreen(
                        routingState = uiState.routingState,
                        nodes = uiState.routingNodes,
                        testingNodeId = uiState.testingNodeId,
                        latencyResults = uiState.nodeLatencyResults,
                        onSaveNode = viewModel::saveRoutingNode,
                        onDeleteNode = viewModel::deleteRoutingNode,
                        onTestNode = viewModel::testRoutingNode,
                        onConnectNode = viewModel::connectRoutingNode,
                        onDisconnect = viewModel::disconnectRouting,
                        onToggleVpnMode = viewModel::setRoutingTunnelMode
                    )

                    TvSection.PLAYLISTS -> PlaylistsAndFilterManagerScreen(
                        playlists = uiState.playlists,
                        activePlaylistId = uiState.activePlaylistId,
                        allCategories = uiState.allCategories,
                        mediaItems = uiState.mediaItems,
                        onFetchAndFilter = viewModel::addOrEditPlaylistAndFetchCategories,
                        onSelectActivePlaylist = { sourceId ->
                            viewModel.selectActivePlaylist(sourceId, navigateToContent = true)
                        },
                        onOpenSavedFilter = { src -> viewModel.openCategoryFilterForExistingPlaylist(src, refreshFromServer = false) },
                        onRefreshServerCategories = { src -> viewModel.openCategoryFilterForExistingPlaylist(src, refreshFromServer = true) },
                        onRefreshEpg = viewModel::refreshEpgForPlaylist,
                        onDeletePlaylist = viewModel::deletePlaylist
                    )
                }
            }
        }

        if (showPlaylistSwitcher && uiState.playlists.size > 1) {
            PlaylistSwitcherOverlay(
                playlists = uiState.playlists,
                activePlaylistId = uiState.activePlaylistId,
                mediaItems = uiState.mediaItems,
                onSelectPlaylist = { id ->
                    showPlaylistSwitcher = false
                    viewModel.selectActivePlaylist(id, navigateToContent = false)
                },
                onClose = { showPlaylistSwitcher = false }
            )
        }

        GlobalBusyAndStatusOverlay(
            isBusy = uiState.isBusy,
            busyMessage = uiState.busyMessage,
            bannerMessage = uiState.bannerMessage,
            errorMessage = uiState.errorMessage,
            onDismissBanner = viewModel::dismissBanner
        )
    }
}

/**
 * Playlists & Pre-Load Filter Manager:
 * Supports Stalker Portal (MAC, Serial Number, Device ID1, Device ID2, Signature),
 * Xtream Codes API, M3U/M3U8 URL, Local M3U File (SAF), and GitHub Gist JSON.
 */
@Composable
private fun PlaylistsAndFilterManagerScreen(
    playlists: List<PlaylistSource>,
    activePlaylistId: String?,
    allCategories: List<com.example.models.ContentCategory>,
    mediaItems: List<MediaItem>,
    onFetchAndFilter: (PlaylistSource) -> Unit,
    onSelectActivePlaylist: (String?) -> Unit,
    onOpenSavedFilter: (PlaylistSource) -> Unit,
    onRefreshServerCategories: (PlaylistSource) -> Unit,
    onRefreshEpg: (PlaylistSource) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    val context = LocalContext.current
    var sourceType by remember { mutableStateOf(SourceType.STALKER) }
    var name by remember { mutableStateOf("") }
    var stalkerPortalUrl by remember { mutableStateOf("") }
    var xtreamServerUrl by remember { mutableStateOf("") }
    var macAddress by remember { mutableStateOf("00:1A:79:") }
    var serialNumber by remember { mutableStateOf("") }
    var deviceId1 by remember { mutableStateOf("") }
    var deviceId2 by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var localFileUri by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            localFileUri = uri.toString()
            if (name.isBlank()) {
                name = "Local M3U Playlist"
            }
        }
    }

    val firstSourceChipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(70L)
        runCatching { firstSourceChipFocus.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Left Column (54%): Add New IPTV Source Form
        Column(
            modifier = Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianBase)
                .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add IPTV Playlist Source", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text(
                "Add multiple Stalker Portals, Xtream Codes accounts, or M3U playlists. Existing playlists stay saved and you can switch anytime.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            // Source Type Selector Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceType.entries.forEachIndexed { idx, type ->
                    val shortLabel = when (type) {
                        SourceType.STALKER -> "Stalker MAG"
                        SourceType.XTREAM -> "Xtream"
                        SourceType.M3U_URL -> "M3U URL"
                        SourceType.M3U_FILE -> "M3U File"
                        SourceType.GIST_JSON -> "Gist JSON"
                    }
                    TvFocusableSurface(
                        onClick = {
                            sourceType = type
                            formError = null
                        },
                        selected = sourceType == type,
                        focusScale = 1.04f,
                        containerColor = MidnightPanel,
                        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.55f),
                        focusedContainerColor = BroadcastCyan,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                        modifier = Modifier
                            .then(if (idx == 0) Modifier.focusRequester(firstSourceChipFocus) else Modifier)
                            .testTag("source_type_${type.name.lowercase()}")
                    ) { isFocused ->
                        Text(
                            text = shortLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isFocused) OnCyanDark else if (sourceType == type) TextPrimary else TextSecondary
                        )
                    }
                }
            }

            TvTextField(
                label = "Playlist Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Living Room Stalker / Home Xtream",
                testTag = "playlist_name_input"
            )

            when (sourceType) {
                SourceType.STALKER -> {
                    TvTextField(
                        label = "Portal URL",
                        value = stalkerPortalUrl,
                        onValueChange = { stalkerPortalUrl = it },
                        placeholder = "http://portal.example.com/c/ or /portal.php",
                        testTag = "stalker_portal_url_input"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            label = "MAC Address",
                            value = macAddress,
                            onValueChange = { macAddress = it },
                            placeholder = "00:1A:79:XX:XX:XX",
                            modifier = Modifier.weight(1f),
                            testTag = "stalker_mac_input"
                        )
                        TvTextField(
                            label = "Serial Number (SN)",
                            value = serialNumber,
                            onValueChange = { serialNumber = it },
                            placeholder = "Optional STB Serial",
                            modifier = Modifier.weight(1f),
                            testTag = "stalker_sn_input"
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            label = "Device ID 1",
                            value = deviceId1,
                            onValueChange = { deviceId1 = it },
                            placeholder = "Optional 64-hex Device ID1",
                            modifier = Modifier.weight(1f),
                            testTag = "stalker_devid1_input"
                        )
                        TvTextField(
                            label = "Device ID 2",
                            value = deviceId2,
                            onValueChange = { deviceId2 = it },
                            placeholder = "Optional 64-hex Device ID2",
                            modifier = Modifier.weight(1f),
                            testTag = "stalker_devid2_input"
                        )
                    }
                    TvTextField(
                        label = "Signature (Optional)",
                        value = signature,
                        onValueChange = { signature = it },
                        placeholder = "Optional STB signature",
                        testTag = "stalker_signature_input"
                    )
                }

                SourceType.XTREAM -> {
                    TvTextField(
                        label = "Server URL",
                        value = xtreamServerUrl,
                        onValueChange = { xtreamServerUrl = it },
                        placeholder = "http://server.example.com:8080",
                        testTag = "xtream_url_input"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            label = "Username",
                            value = username,
                            onValueChange = { username = it },
                            placeholder = "Xtream username",
                            modifier = Modifier.weight(1f),
                            testTag = "xtream_user_input"
                        )
                        TvTextField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "Xtream password",
                            isPassword = true,
                            modifier = Modifier.weight(1f),
                            testTag = "xtream_pass_input"
                        )
                    }
                }

                SourceType.M3U_URL -> {
                    TvTextField(
                        label = "M3U / M3U8 Playlist URL",
                        value = m3uUrl,
                        onValueChange = { m3uUrl = it },
                        placeholder = "https://example.com/playlist.m3u8",
                        testTag = "m3u_url_input"
                    )
                }

                SourceType.M3U_FILE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvActionButton(
                            text = "Pick Local .m3u / .m3u8 File",
                            icon = Icons.Default.FolderOpen,
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            testTag = "m3u_pick_file_button"
                        )
                        Text(
                            text = if (localFileUri.isBlank()) "No file selected" else localFileUri,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (localFileUri.isBlank()) TextMuted else BroadcastCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                SourceType.GIST_JSON -> {
                    TvTextField(
                        label = "GitHub Gist JSON URL",
                        value = m3uUrl,
                        onValueChange = { m3uUrl = it },
                        placeholder = "https://gist.githubusercontent.com/.../raw/playlist.json",
                        testTag = "gist_url_input"
                    )
                }
            }

            TvTextField(
                label = "XMLTV EPG URL (Optional)",
                value = epgUrl,
                onValueChange = { epgUrl = it },
                placeholder = "https://example.com/epg.xml.gz",
                testTag = "playlist_epg_input"
            )

            if (formError != null) {
                Text(formError!!, style = MaterialTheme.typography.bodyMedium, color = LiveCrimson)
            }

            Spacer(Modifier.height(4.dp))
            TvActionButton(
                text = "Fetch Categories & Open Pre-Load Filter",
                icon = Icons.Default.AddCircle,
                isPrimary = true,
                onClick = {
                    val activeUrl = if (sourceType == SourceType.STALKER) stalkerPortalUrl else xtreamServerUrl
                    val validationError = when (sourceType) {
                        SourceType.STALKER -> when {
                            stalkerPortalUrl.isBlank() -> "Enter a valid Stalker Portal URL."
                            macAddress.trim().length < 11 -> "Enter a valid MAG MAC address (e.g. 00:1A:79:XX:XX:XX)."
                            else -> null
                        }
                        SourceType.XTREAM -> when {
                            xtreamServerUrl.isBlank() || username.isBlank() || password.isBlank() ->
                                "Enter Server URL, Username, and Password."
                            else -> null
                        }
                        SourceType.M3U_URL -> if (m3uUrl.isBlank()) "Enter an M3U/M3U8 URL." else null
                        SourceType.M3U_FILE -> if (localFileUri.isBlank()) "Pick a local M3U/M3U8 file first." else null
                        SourceType.GIST_JSON -> if (m3uUrl.isBlank()) "Enter a GitHub Gist JSON URL." else null
                    }
                    if (validationError != null) {
                        formError = validationError
                        return@TvActionButton
                    }
                    formError = null
                    val finalName = name.trim().ifBlank {
                        val sameTypeCount = playlists.count { it.type == sourceType }
                        if (sameTypeCount == 0) sourceType.displayName else "${sourceType.displayName} #${sameTypeCount + 1}"
                    }
                    onFetchAndFilter(
                        PlaylistSource(
                            name = finalName,
                            type = sourceType,
                            portalUrl = activeUrl.trim(),
                            macAddress = if (sourceType == SourceType.STALKER) macAddress.trim().uppercase() else "",
                            serialNumber = if (sourceType == SourceType.STALKER) serialNumber.trim() else "",
                            deviceId1 = if (sourceType == SourceType.STALKER) deviceId1.trim() else "",
                            deviceId2 = if (sourceType == SourceType.STALKER) deviceId2.trim() else "",
                            signature = if (sourceType == SourceType.STALKER) signature.trim() else "",
                            username = if (sourceType == SourceType.XTREAM) username.trim() else "",
                            password = if (sourceType == SourceType.XTREAM) password.trim() else "",
                            m3uUrl = m3uUrl.trim(),
                            localFileUri = localFileUri.trim(),
                            epgUrl = epgUrl.trim()
                        )
                    )
                    // Reset name field for adding the next playlist cleanly
                    name = ""
                },
                modifier = Modifier.fillMaxWidth(),
                testTag = "playlist_fetch_categories_button"
            )
        }

        // Right Column (46%): Configured Playlists + Instant Switcher + Filter Controls
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianBase)
                .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Saved Playlists", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    TvBadge(text = "${playlists.size}")
                }
                if (playlists.size > 1) {
                    val isAllMode = activePlaylistId == null
                    TvActionButton(
                        text = if (isAllMode) "All Active" else "Show All",
                        icon = Icons.Default.Layers,
                        isPrimary = isAllMode,
                        onClick = { onSelectActivePlaylist(null) },
                        testTag = "playlist_show_all_button"
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Link, contentDescription = "No playlists", tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No playlists saved yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                        Text(
                            "Configure any source on the left to fetch its categories.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists, key = { it.id }) { source ->
                        val cats = allCategories.filter { it.sourceId == source.id }
                        val checkedCount = cats.count { it.isSelected }
                        val sourceItemsCount = mediaItems.count { it.sourceId == source.id }
                        val isCurrentlyActive = activePlaylistId == source.id || (playlists.size == 1)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrentlyActive) BroadcastCyanDeep.copy(alpha = 0.16f) else MidnightPanel)
                                .border(
                                    width = if (isCurrentlyActive) 1.5.dp else 1.dp,
                                    color = if (isCurrentlyActive) BroadcastCyan else DividerSlate,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = source.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isCurrentlyActive) {
                                            TvBadge(
                                                text = "ACTIVE",
                                                containerColor = ConnectedEmerald.copy(alpha = 0.22f),
                                                contentColor = ConnectedEmerald
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${source.type.displayName} • $sourceItemsCount items • $checkedCount/${cats.size} groups",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BroadcastCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TvActionButton(
                                    text = if (isCurrentlyActive) "Watch" else "Switch",
                                    icon = if (isCurrentlyActive) Icons.Default.CheckCircle else Icons.Default.SwapHoriz,
                                    isPrimary = !isCurrentlyActive,
                                    onClick = { onSelectActivePlaylist(source.id) },
                                    modifier = Modifier.weight(1f),
                                    testTag = "playlist_switch_${source.id}"
                                )
                                TvActionButton(
                                    text = "Filter",
                                    icon = Icons.Default.FilterList,
                                    onClick = { onOpenSavedFilter(source) },
                                    testTag = "playlist_edit_filter_${source.id}"
                                )
                                TvActionButton(
                                    text = "",
                                    icon = Icons.Default.Refresh,
                                    onClick = { onRefreshServerCategories(source) },
                                    testTag = "playlist_sync_${source.id}"
                                )
                                TvActionButton(
                                    text = "EPG",
                                    onClick = { onRefreshEpg(source) },
                                    testTag = "playlist_epg_${source.id}"
                                )
                                TvActionButton(
                                    text = "",
                                    icon = Icons.Default.Delete,
                                    isDanger = true,
                                    onClick = { onDeletePlaylist(source.id) },
                                    testTag = "playlist_delete_${source.id}"
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
 * Country / Proxy Routing Module (`VpnService` + `OkHttpClient` Proxy).
 */
@Composable
private fun CountryProxyRoutingScreen(
    routingState: RoutingState,
    nodes: List<RoutingNode>,
    testingNodeId: String?,
    latencyResults: Map<String, String>,
    onSaveNode: (RoutingNode) -> Unit,
    onDeleteNode: (String) -> Unit,
    onTestNode: (RoutingNode) -> Unit,
    onConnectNode: (RoutingNode) -> Unit,
    onDisconnect: () -> Unit,
    onToggleVpnMode: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var pendingConnectNode by remember { mutableStateOf<RoutingNode?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val node = pendingConnectNode
        pendingConnectNode = null
        if (result.resultCode == Activity.RESULT_OK && node != null) {
            onConnectNode(node)
        }
    }

    var nodeName by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("IN") }
    var countryName by remember { mutableStateOf("India") }
    var host by remember { mutableStateOf("") }
    var portStr by remember { mutableStateOf("8080") }
    var protocol by remember { mutableStateOf(ProxyProtocol.HTTP) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val routingEntryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(70L)
        runCatching { routingEntryFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Routing Status Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (routingState.isConnected) ConnectedEmerald.copy(alpha = 0.14f) else ObsidianBase)
                .border(
                    1.5.dp,
                    if (routingState.isConnected) ConnectedEmerald else DividerSlate,
                    RoundedCornerShape(14.dp)
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (routingState.isConnected) Icons.Default.CheckCircle else Icons.Default.Router,
                    contentDescription = "Routing status",
                    tint = if (routingState.isConnected) ConnectedEmerald else BroadcastCyan,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (routingState.isConnected) "Country / Proxy Routing Connected" else "Country / Proxy Routing",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = routingState.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (routingState.isConnected) ConnectedEmerald else TextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    text = if (routingState.useVpnTunnel) "Mode: VPN + OkHttp" else "Mode: App Proxy Only",
                    icon = Icons.Default.VpnKey,
                    onClick = { onToggleVpnMode(!routingState.useVpnTunnel) },
                    modifier = Modifier.focusRequester(routingEntryFocus),
                    testTag = "routing_mode_toggle_button"
                )
                if (routingState.isConnected || routingState.isConnecting) {
                    TvActionButton(
                        text = "Disconnect",
                        icon = Icons.Default.PowerSettingsNew,
                        isDanger = true,
                        onClick = onDisconnect,
                        testTag = "routing_disconnect_button"
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Left: Add Proxy / Country Node Form
            Column(
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBase)
                    .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add Routing Node", style = MaterialTheme.typography.titleLarge, color = TextPrimary)

                // Protocol selector (HTTP vs SOCKS5)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyProtocol.entries.forEach { proto ->
                        TvFocusableSurface(
                            onClick = { protocol = proto },
                            selected = protocol == proto,
                            focusScale = 1.04f,
                            containerColor = MidnightPanel,
                            selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.55f),
                            focusedContainerColor = BroadcastCyan,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("routing_proto_${proto.name.lowercase()}")
                        ) { isFocused ->
                            Text(
                                text = proto.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isFocused) OnCyanDark else TextPrimary
                            )
                        }
                    }
                }

                TvTextField(
                    label = "Node Label",
                    value = nodeName,
                    onValueChange = { nodeName = it },
                    placeholder = "e.g. Mumbai Fast Proxy",
                    testTag = "routing_name_input"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField(
                        label = "Country Code",
                        value = countryCode,
                        onValueChange = { countryCode = it.uppercase().take(3) },
                        placeholder = "IN / UK / US",
                        modifier = Modifier.weight(0.35f),
                        testTag = "routing_cc_input"
                    )
                    TvTextField(
                        label = "Country / Region",
                        value = countryName,
                        onValueChange = { countryName = it },
                        placeholder = "India / UK / UAE",
                        modifier = Modifier.weight(0.65f),
                        testTag = "routing_country_input"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField(
                        label = "Proxy Host / IP",
                        value = host,
                        onValueChange = { host = it },
                        placeholder = "proxy.example.com",
                        modifier = Modifier.weight(0.68f),
                        testTag = "routing_host_input"
                    )
                    TvTextField(
                        label = "Port",
                        value = portStr,
                        onValueChange = { portStr = it.filter(Char::isDigit).take(5) },
                        placeholder = "8080",
                        modifier = Modifier.weight(0.32f),
                        testTag = "routing_port_input"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField(
                        label = "Username (Optional)",
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "Proxy user",
                        modifier = Modifier.weight(1f)
                    )
                    TvTextField(
                        label = "Password (Optional)",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Proxy pass",
                        isPassword = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (formError != null) {
                    Text(formError!!, style = MaterialTheme.typography.bodySmall, color = LiveCrimson)
                }

                TvActionButton(
                    text = "Save Routing Node",
                    icon = Icons.Default.AddCircle,
                    isPrimary = true,
                    onClick = {
                        val port = portStr.toIntOrNull() ?: 0
                        if (host.isBlank() || port !in 1..65535) {
                            formError = "Enter a valid proxy host and port (1-65535)."
                            return@TvActionButton
                        }
                        formError = null
                        onSaveNode(
                            RoutingNode(
                                name = nodeName.trim().ifBlank { "${countryName.ifBlank { "Custom" }} Proxy" },
                                countryCode = countryCode.trim().ifBlank { "GL" },
                                countryName = countryName.trim().ifBlank { "Global" },
                                host = host.trim(),
                                port = port,
                                protocol = protocol,
                                username = username.trim(),
                                password = password.trim()
                            )
                        )
                        nodeName = ""
                        host = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "routing_save_node_button"
                )
            }

            // Right: Saved Proxy / Country Nodes List
            Column(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBase)
                    .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Text("Saved Country / Proxy Nodes (${nodes.size})", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(10.dp))

                if (nodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Dns, contentDescription = "No nodes", tint = TextMuted, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No proxy nodes configured", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                            Text(
                                "Add an HTTP or SOCKS5 proxy node on the left to route IPTV portals & streams through a chosen country.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(nodes, key = { it.id }) { node ->
                            val isActive = routingState.isConnected && routingState.activeNode?.id == node.id
                            val latency = latencyResults[node.id]
                            val isTesting = testingNodeId == node.id

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isActive) ConnectedEmerald.copy(alpha = 0.14f) else MidnightPanel)
                                    .border(
                                        1.5.dp,
                                        if (isActive) ConnectedEmerald else DividerSlate,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        TvBadge(text = node.countryCode, contentColor = BroadcastCyan)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(node.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                            Text(
                                                "${node.countryName} • ${node.protocol.label} ${node.host}:${node.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (isActive) {
                                        TvBadge(
                                            text = "ACTIVE",
                                            containerColor = ConnectedEmerald.copy(alpha = 0.2f),
                                            contentColor = ConnectedEmerald
                                        )
                                    }
                                }

                                if (latency != null) {
                                    Text(
                                        text = "Probe: $latency",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (latency.startsWith("Failed")) SignalAmber else ConnectedEmerald
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TvActionButton(
                                        text = if (isActive) "Disconnect" else "Connect",
                                        icon = Icons.Default.PowerSettingsNew,
                                        isPrimary = !isActive,
                                        isDanger = isActive,
                                        onClick = {
                                            if (isActive) {
                                                onDisconnect()
                                            } else if (routingState.useVpnTunnel) {
                                                val prepIntent = RoutingManager.prepareVpnIntent(context)
                                                if (prepIntent != null) {
                                                    pendingConnectNode = node
                                                    vpnPermissionLauncher.launch(prepIntent)
                                                } else {
                                                    onConnectNode(node)
                                                }
                                            } else {
                                                onConnectNode(node)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        testTag = "routing_connect_${node.id}"
                                    )
                                    TvActionButton(
                                        text = if (isTesting) "Testing..." else "Ping",
                                        icon = Icons.Default.Speed,
                                        enabled = !isTesting,
                                        onClick = { onTestNode(node) },
                                        testTag = "routing_test_${node.id}"
                                    )
                                    TvActionButton(
                                        text = "",
                                        icon = Icons.Default.Delete,
                                        isDanger = true,
                                        onClick = { onDeleteNode(node.id) },
                                        testTag = "routing_delete_${node.id}"
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
private fun GlobalBusyAndStatusOverlay(
    isBusy: Boolean,
    busyMessage: String,
    bannerMessage: String?,
    errorMessage: String?,
    onDismissBanner: () -> Unit
) {
    val activeMsg = errorMessage ?: bannerMessage

    androidx.compose.runtime.LaunchedEffect(activeMsg) {
        if (!activeMsg.isNullOrBlank()) {
            kotlinx.coroutines.delay(if (errorMessage != null) 4500L else 2800L)
            onDismissBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !activeMsg.isNullOrBlank(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
        ) {
            val isErr = errorMessage != null
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isErr) LiveCrimson.copy(alpha = 0.92f) else ElevatedSlate.copy(alpha = 0.95f))
                    .border(
                        1.dp,
                        if (isErr) Color.White.copy(alpha = 0.5f) else DividerSlate,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = activeMsg.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(360.dp)
                )
            }
        }

        if (isBusy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.80f)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianBase)
                        .border(1.dp, DividerSlate, RoundedCornerShape(14.dp))
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    CircularProgressIndicator(color = BroadcastCyan, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                    Text(
                        text = busyMessage.ifBlank { "Working..." },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

/**
 * TV D-Pad Playlist Switcher Modal:
 * Lets the user switch cleanly between any saved Xtream Codes, Stalker Portal, M3U,
 * or Gist playlist, or select "All Playlists (Combined)".
 */
@Composable
private fun PlaylistSwitcherOverlay(
    playlists: List<PlaylistSource>,
    activePlaylistId: String?,
    mediaItems: List<MediaItem>,
    onSelectPlaylist: (String?) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    val activeItemFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L)
        runCatching { activeItemFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.80f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianBase)
                .border(1.5.dp, BroadcastCyan.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                .padding(24.dp)
                .testTag("playlist_switcher_modal"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch Playlist",
                        tint = BroadcastCyan,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text(
                            text = "Switch IPTV Playlist",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Choose Xtream, Stalker, or view All Playlists combined",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
                TvActionButton(
                    text = "Close",
                    onClick = onClose,
                    testTag = "playlist_switcher_close"
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(playlists, key = { it.id }) { source ->
                    val isSelected = activePlaylistId == source.id
                    val liveCount = mediaItems.count { it.sourceId == source.id && it.type == ContentType.LIVE }
                    val vodCount = mediaItems.count { it.sourceId == source.id && it.type == ContentType.VOD }
                    val seriesCount = mediaItems.count { it.sourceId == source.id && it.type == ContentType.SERIES }

                    TvFocusableSurface(
                        onClick = { onSelectPlaylist(source.id) },
                        selected = isSelected,
                        focusScale = 1.02f,
                        shape = RoundedCornerShape(10.dp),
                        containerColor = MidnightPanel,
                        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.36f),
                        focusedContainerColor = HighlightSlate,
                        borderColor = if (isSelected) BroadcastCyan else DividerSlate.copy(alpha = 0.4f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isSelected) Modifier.focusRequester(activeItemFocus) else Modifier)
                            .testTag("switcher_option_${source.id}")
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = source.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TvBadge(
                                        text = when (source.type) {
                                            SourceType.STALKER -> "STALKER"
                                            SourceType.XTREAM -> "XTREAM"
                                            SourceType.M3U_URL -> "M3U"
                                            SourceType.M3U_FILE -> "FILE"
                                            SourceType.GIST_JSON -> "GIST"
                                        }
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "$liveCount Live • $vodCount Movies • $seriesCount Series",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isFocused) TextSecondary else TextMuted
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active playlist",
                                    tint = ConnectedEmerald,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                item(key = "switcher_all_playlists") {
                    val isAllSelected = activePlaylistId == null
                    TvFocusableSurface(
                        onClick = { onSelectPlaylist(null) },
                        selected = isAllSelected,
                        focusScale = 1.02f,
                        shape = RoundedCornerShape(10.dp),
                        containerColor = MidnightPanel,
                        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.36f),
                        focusedContainerColor = HighlightSlate,
                        borderColor = if (isAllSelected) BroadcastCyan else DividerSlate.copy(alpha = 0.4f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isAllSelected) Modifier.focusRequester(activeItemFocus) else Modifier)
                            .testTag("switcher_option_all")
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "All playlists",
                                    tint = BroadcastCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "All Playlists Combined (${playlists.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        fontWeight = if (isAllSelected || isFocused) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${mediaItems.size} total imported items across all sources",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isFocused) TextSecondary else TextMuted
                                    )
                                }
                            }
                            if (isAllSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "All active",
                                    tint = ConnectedEmerald,
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
