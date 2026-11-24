package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.IptvRepository
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.EpgProgram
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.ResolvedStream
import com.example.models.RoutingNode
import com.example.models.RoutingState
import com.example.models.SeasonGroup
import com.example.models.SourceType
import com.example.models.TvSection
import com.example.routing.RoutingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IptvUiState(
    val currentSection: TvSection = TvSection.LIVE_TV,
    val playlists: List<PlaylistSource> = emptyList(),
    /** Null means "All Playlists (Combined)" when multiple playlists exist; otherwise specific playlist ID. */
    val activePlaylistId: String? = null,
    val selectedCategories: List<ContentCategory> = emptyList(),
    val allCategories: List<ContentCategory> = emptyList(),
    val mediaItems: List<MediaItem> = emptyList(),
    val routingNodes: List<RoutingNode> = emptyList(),
    val routingState: RoutingState = RoutingState(),
    val epgByChannelId: Map<String, List<EpgProgram>> = emptyMap(),

    // 2-Step Country & Category Pre-Load Filter
    val isFilterScreenOpen: Boolean = false,
    val filterSource: PlaylistSource? = null,
    val filterDraftCategories: List<ContentCategory> = emptyList(),

    // Live TV 3-Pane State
    val selectedLiveCategoryId: String = "ALL",
    val previewChannel: MediaItem? = null,
    val previewStream: ResolvedStream? = null,
    val isPreviewResolving: Boolean = false,

    // VOD & Series Selection State
    val selectedVodCategoryId: String = "ALL",
    val selectedSeriesCategoryId: String = "ALL",
    val detailItem: MediaItem? = null,
    val seriesSeasons: List<SeasonGroup> = emptyList(),
    val isLoadingSeasons: Boolean = false,

    // Full-Screen Media3 Player State
    val playerItem: MediaItem? = null,
    val playerStream: ResolvedStream? = null,
    val isResolvingPlayerStream: Boolean = false,
    val playerError: String? = null,
    val liveZapQueue: List<MediaItem> = emptyList(),

    // Global Progress & Notifications
    val isBusy: Boolean = false,
    val busyMessage: String = "",
    val bannerMessage: String? = null,
    val errorMessage: String? = null,
    val testingNodeId: String? = null,
    val nodeLatencyResults: Map<String, String> = emptyMap()
) {
    val activePlaylist: PlaylistSource?
        get() = activePlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }

    val activePlaylistLabel: String
        get() = when {
            playlists.isEmpty() -> "No Playlist"
            activePlaylist != null -> activePlaylist!!.name
            playlists.size > 1 -> "All Playlists (${playlists.size})"
            else -> playlists.first().name
        }

    val visibleSelectedCategories: List<ContentCategory>
        get() = if (activePlaylistId == null) {
            selectedCategories
        } else {
            selectedCategories.filter { it.sourceId == activePlaylistId }
        }

    val visibleMediaItems: List<MediaItem>
        get() = if (activePlaylistId == null) {
            mediaItems
        } else {
            mediaItems.filter { it.sourceId == activePlaylistId }
        }
}

class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IptvRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(IptvUiState())
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var playerResolveJob: Job? = null
    private var epgFetchJob: Job? = null

    init {
        observeLocalData()
    }

    private fun observeLocalData() {
        viewModelScope.launch {
            combine(
                repository.playlistsFlow,
                repository.selectedCategoriesFlow,
                repository.allCategoriesFlow,
                repository.mediaItemsFlow,
                repository.routingNodesFlow
            ) { playlists, selectedCats, allCats, items, nodes ->
                RoutingManager.setNodes(nodes)
                DataSnapshot(playlists, selectedCats, allCats, items, nodes)
            }.collect { snap ->
                _uiState.update { state ->
                    val derivedActiveId: String? = when {
                        snap.playlists.isEmpty() -> null
                        snap.playlists.size == 1 -> snap.playlists.first().id
                        else -> {
                            val activeEntries = snap.playlists.filter { it.isActive }
                            when {
                                activeEntries.size == 1 -> activeEntries.first().id
                                activeEntries.size == snap.playlists.size -> null // Combined All Playlists mode
                                activeEntries.isNotEmpty() -> activeEntries.first().id
                                else -> snap.playlists.first().id
                            }
                        }
                    }

                    val visibleItems = if (derivedActiveId == null) {
                        snap.items
                    } else {
                        snap.items.filter { it.sourceId == derivedActiveId }
                    }

                    val refreshedPreview = state.previewChannel?.let { prev ->
                        visibleItems.firstOrNull { it.id == prev.id }
                    }
                    val refreshedDetail = state.detailItem?.let { det ->
                        snap.items.firstOrNull { it.id == det.id } ?: det
                    }
                    val refreshedPlayer = state.playerItem?.let { pl ->
                        snap.items.firstOrNull { it.id == pl.id } ?: pl
                    }
                    state.copy(
                        playlists = snap.playlists,
                        activePlaylistId = derivedActiveId,
                        selectedCategories = snap.selectedCats,
                        allCategories = snap.allCats,
                        mediaItems = snap.items,
                        routingNodes = snap.nodes,
                        previewChannel = refreshedPreview,
                        previewStream = if (refreshedPreview == null) null else state.previewStream,
                        detailItem = refreshedDetail,
                        playerItem = refreshedPlayer
                    )
                }
            }
        }

        viewModelScope.launch {
            RoutingManager.routingState.collect { routing ->
                _uiState.update { it.copy(routingState = routing) }
            }
        }
    }

    private data class DataSnapshot(
        val playlists: List<PlaylistSource>,
        val selectedCats: List<ContentCategory>,
        val allCats: List<ContentCategory>,
        val items: List<MediaItem>,
        val nodes: List<RoutingNode>
    )

    // -------------------------------------------------------------------------
    // NAVIGATION
    // -------------------------------------------------------------------------

    fun selectSection(section: TvSection) {
        _uiState.update {
            it.copy(
                currentSection = section,
                detailItem = null,
                errorMessage = null
            )
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null, errorMessage = null) }
    }

    // -------------------------------------------------------------------------
    // STEP 1 & STEP 2: PLAYLIST IMPORT & COUNTRY/CATEGORY PRE-LOAD FILTER
    // -------------------------------------------------------------------------

    fun addOrEditPlaylistAndFetchCategories(source: PlaylistSource) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Connecting to ${source.name} (${source.type.displayName}) and fetching categories...",
                    errorMessage = null
                )
            }
            runCatching {
                repository.fetchCategoriesForFilter(source)
            }.onSuccess { categories ->
                if (categories.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyMessage = "",
                            errorMessage = "Connected, but no categories were returned. Check credentials or URL."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyMessage = "",
                            isFilterScreenOpen = true,
                            filterSource = source,
                            filterDraftCategories = categories,
                            bannerMessage = "Loaded ${categories.size} categories. Select countries & categories to import."
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorMessage = err.message ?: "Failed to connect to playlist source"
                    )
                }
            }
        }
    }

    fun openCategoryFilterForExistingPlaylist(source: PlaylistSource, refreshFromServer: Boolean = false) {
        viewModelScope.launch {
            if (refreshFromServer) {
                addOrEditPlaylistAndFetchCategories(source)
                return@launch
            }
            val saved = repository.getSavedCategoriesForSource(source.id)
            if (saved.isEmpty()) {
                addOrEditPlaylistAndFetchCategories(source)
            } else {
                _uiState.update {
                    it.copy(
                        isFilterScreenOpen = true,
                        filterSource = source,
                        filterDraftCategories = saved,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun closeCategoryFilter() {
        _uiState.update {
            it.copy(
                isFilterScreenOpen = false,
                filterSource = null,
                filterDraftCategories = emptyList()
            )
        }
    }

    fun toggleDraftCategory(categoryId: String) {
        _uiState.update { state ->
            state.copy(
                filterDraftCategories = state.filterDraftCategories.map { cat ->
                    if (cat.id == categoryId) cat.copy(isSelected = !cat.isSelected) else cat
                }
            )
        }
    }

    fun setDraftCountrySelection(type: ContentType, countryGroup: String, selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                filterDraftCategories = state.filterDraftCategories.map { cat ->
                    if (cat.type == type && cat.countryGroup == countryGroup) {
                        cat.copy(isSelected = selected)
                    } else {
                        cat
                    }
                }
            )
        }
    }

    fun setDraftTabSelection(type: ContentType, selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                filterDraftCategories = state.filterDraftCategories.map { cat ->
                    if (cat.type == type) cat.copy(isSelected = selected) else cat
                }
            )
        }
    }

    fun confirmAndImportSelectedCategories() {
        val source = _uiState.value.filterSource ?: return
        val draftCategories = _uiState.value.filterDraftCategories
        val selectedCount = draftCategories.count { it.isSelected }
        if (selectedCount == 0) {
            _uiState.update {
                it.copy(errorMessage = "Tick at least one category before importing.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Importing content for $selectedCount selected categories (excluding unchecked)...",
                    errorMessage = null
                )
            }
            runCatching {
                repository.importSelectedCategories(source, draftCategories)
            }.onSuccess { importedCount ->
                val hasLive = draftCategories.any { it.isSelected && it.type == ContentType.LIVE }
                val hasVod = draftCategories.any { it.isSelected && it.type == ContentType.VOD }
                val targetSection = when {
                    hasLive -> TvSection.LIVE_TV
                    hasVod -> TvSection.MOVIES
                    else -> TvSection.WEB_SERIES
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        isFilterScreenOpen = false,
                        filterSource = null,
                        filterDraftCategories = emptyList(),
                        activePlaylistId = source.id,
                        currentSection = targetSection,
                        selectedLiveCategoryId = "ALL",
                        selectedVodCategoryId = "ALL",
                        selectedSeriesCategoryId = "ALL",
                        previewChannel = null,
                        previewStream = null,
                        bannerMessage = null
                    )
                }
                refreshEpgForPlaylist(source)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorMessage = err.message ?: "Import failed"
                    )
                }
            }
        }
    }

    /**
     * Instantly switches between saved playlists (e.g. Xtream Codes <-> Stalker Portal <-> All Playlists)
     * without deleting or modifying any saved playlists.
     */
    fun selectActivePlaylist(sourceId: String?, navigateToContent: Boolean = false) {
        previewJob?.cancel()
        viewModelScope.launch {
            repository.setActivePlaylist(sourceId)
            val currentState = _uiState.value
            val targetItems = if (sourceId == null) {
                currentState.mediaItems
            } else {
                currentState.mediaItems.filter { it.sourceId == sourceId }
            }
            val nextSection = if (navigateToContent && currentState.currentSection == TvSection.PLAYLISTS) {
                when {
                    targetItems.any { it.type == ContentType.LIVE } -> TvSection.LIVE_TV
                    targetItems.any { it.type == ContentType.VOD } -> TvSection.MOVIES
                    targetItems.any { it.type == ContentType.SERIES } -> TvSection.WEB_SERIES
                    else -> TvSection.LIVE_TV
                }
            } else {
                currentState.currentSection
            }
            _uiState.update { state ->
                state.copy(
                    activePlaylistId = sourceId,
                    currentSection = nextSection,
                    selectedLiveCategoryId = "ALL",
                    selectedVodCategoryId = "ALL",
                    selectedSeriesCategoryId = "ALL",
                    previewChannel = null,
                    previewStream = null,
                    detailItem = null
                )
            }
        }
    }

    fun deletePlaylist(sourceId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(sourceId)
            _uiState.update {
                it.copy(
                    previewChannel = if (it.previewChannel?.sourceId == sourceId) null else it.previewChannel,
                    previewStream = if (it.previewChannel?.sourceId == sourceId) null else it.previewStream,
                    bannerMessage = "Playlist removed."
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // LIVE TV, PREVIEW & EPG
    // -------------------------------------------------------------------------

    fun selectLiveCategory(categoryId: String) {
        _uiState.update { it.copy(selectedLiveCategoryId = categoryId) }
    }

    fun focusOrPreviewChannel(channel: MediaItem, startStreamPreview: Boolean = true) {
        val currentPreview = _uiState.value.previewChannel
        _uiState.update { it.copy(previewChannel = channel) }

        // Load Stalker short EPG on-demand if not yet cached
        val epgKey = channel.epgChannelId.ifBlank { channel.id }
        if (_uiState.value.epgByChannelId[epgKey].isNullOrEmpty()) {
            viewModelScope.launch {
                val shortEpg = repository.fetchStalkerShortEpg(channel)
                if (shortEpg.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(epgByChannelId = state.epgByChannelId + (epgKey to shortEpg))
                    }
                }
            }
        }

        if (!startStreamPreview || (currentPreview?.id == channel.id && _uiState.value.previewStream != null)) {
            return
        }

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _uiState.update { it.copy(isPreviewResolving = true, previewStream = null) }
            runCatching {
                repository.resolvePlayableStream(channel)
            }.onSuccess { resolved ->
                _uiState.update {
                    it.copy(
                        isPreviewResolving = false,
                        previewStream = resolved
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        isPreviewResolving = false,
                        previewStream = null
                    )
                }
            }
        }
    }

    fun refreshEpgForPlaylist(source: PlaylistSource) {
        epgFetchJob?.cancel()
        epgFetchJob = viewModelScope.launch {
            val liveIds = _uiState.value.mediaItems
                .filter { it.sourceId == source.id && it.type == ContentType.LIVE && it.epgChannelId.isNotBlank() }
                .map { it.epgChannelId }
                .toSet()

            runCatching {
                repository.fetchEpgForPlaylist(source, liveIds)
            }.onSuccess { parsed ->
                if (parsed.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(epgByChannelId = state.epgByChannelId + parsed)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // MOVIES & WEB SERIES
    // -------------------------------------------------------------------------

    fun selectVodCategory(categoryId: String) {
        _uiState.update { it.copy(selectedVodCategoryId = categoryId) }
    }

    fun selectSeriesCategory(categoryId: String) {
        _uiState.update { it.copy(selectedSeriesCategoryId = categoryId) }
    }

    fun openMediaDetails(item: MediaItem) {
        _uiState.update {
            it.copy(
                detailItem = item,
                seriesSeasons = emptyList(),
                isLoadingSeasons = item.type == ContentType.SERIES
            )
        }
        if (item.type == ContentType.SERIES) {
            viewModelScope.launch {
                runCatching {
                    repository.fetchSeriesSeasons(item)
                }.onSuccess { seasons ->
                    _uiState.update {
                        it.copy(
                            seriesSeasons = seasons,
                            isLoadingSeasons = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingSeasons = false,
                            errorMessage = err.message ?: "Unable to fetch series episodes"
                        )
                    }
                }
            }
        }
    }

    fun closeMediaDetails() {
        _uiState.update { it.copy(detailItem = null, seriesSeasons = emptyList(), isLoadingSeasons = false) }
    }

    // -------------------------------------------------------------------------
    // FULL-SCREEN MEDIA3 PLAYBACK & LIVE CHANNEL ZAPPING
    // -------------------------------------------------------------------------

    fun openFullScreenPlayer(
        item: MediaItem,
        liveQueue: List<MediaItem> = emptyList(),
        forceRefreshLink: Boolean = false
    ) {
        val queue = if (item.type == ContentType.LIVE) {
            liveQueue.ifEmpty {
                _uiState.value.visibleMediaItems.filter { it.type == ContentType.LIVE }
            }
        } else {
            emptyList()
        }

        // Stop preview player resource before entering full screen
        previewJob?.cancel()
        _uiState.update {
            it.copy(
                previewStream = null,
                playerItem = item,
                playerStream = null,
                isResolvingPlayerStream = true,
                playerError = null,
                liveZapQueue = queue
            )
        }

        resolveAndSetPlayerStream(item, forceRefreshLink)
    }

    fun refreshActivePlayerStream() {
        val current = _uiState.value.playerItem ?: return
        resolveAndSetPlayerStream(current, forceRefreshLink = true)
    }

    private fun resolveAndSetPlayerStream(item: MediaItem, forceRefreshLink: Boolean) {
        playerResolveJob?.cancel()
        playerResolveJob = viewModelScope.launch {
            _uiState.update { it.copy(isResolvingPlayerStream = true, playerError = null) }
            runCatching {
                repository.resolvePlayableStream(item, forceRefresh = forceRefreshLink)
            }.onSuccess { resolved ->
                _uiState.update {
                    it.copy(
                        isResolvingPlayerStream = false,
                        playerStream = resolved,
                        playerError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isResolvingPlayerStream = false,
                        playerError = err.message ?: "Failed to resolve stream URL"
                    )
                }
            }
        }
    }

    fun zapLiveChannel(delta: Int) {
        val state = _uiState.value
        val current = state.playerItem ?: return
        val queue = state.liveZapQueue
        if (queue.size <= 1) return
        val currentIndex = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val nextIndex = ((currentIndex + delta) % queue.size + queue.size) % queue.size
        val nextChannel = queue[nextIndex]
        openFullScreenPlayer(nextChannel, queue)

        val epgKey = nextChannel.epgChannelId.ifBlank { nextChannel.id }
        if (state.epgByChannelId[epgKey].isNullOrEmpty()) {
            viewModelScope.launch {
                val shortEpg = repository.fetchStalkerShortEpg(nextChannel)
                if (shortEpg.isNotEmpty()) {
                    _uiState.update { s ->
                        s.copy(epgByChannelId = s.epgByChannelId + (epgKey to shortEpg))
                    }
                }
            }
        }
    }

    fun closeFullScreenPlayer() {
        playerResolveJob?.cancel()
        _uiState.update {
            it.copy(
                playerItem = null,
                playerStream = null,
                isResolvingPlayerStream = false,
                playerError = null
            )
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            repository.toggleFavorite(item.id, !item.isFavorite)
        }
    }

    fun saveResumePosition(itemId: String, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return
        viewModelScope.launch {
            repository.saveResumePosition(itemId, positionMs, durationMs)
        }
    }

    // -------------------------------------------------------------------------
    // COUNTRY / PROXY ROUTING MODULE
    // -------------------------------------------------------------------------

    fun saveRoutingNode(node: RoutingNode) {
        viewModelScope.launch {
            repository.upsertRoutingNode(node)
            _uiState.update { it.copy(bannerMessage = "Saved routing node: ${node.name} (${node.countryName})") }
        }
    }

    fun deleteRoutingNode(nodeId: String) {
        viewModelScope.launch {
            if (_uiState.value.routingState.activeNode?.id == nodeId) {
                RoutingManager.disconnect(getApplication())
            }
            repository.deleteRoutingNode(nodeId)
        }
    }

    fun setRoutingTunnelMode(useVpnTunnel: Boolean) {
        RoutingManager.setTunnelMode(useVpnTunnel)
    }

    fun testRoutingNode(node: RoutingNode) {
        viewModelScope.launch {
            _uiState.update { it.copy(testingNodeId = node.id) }
            val result = RoutingManager.testNodeLatency(node)
            _uiState.update { state ->
                val text = result.fold(
                    onSuccess = { "${it} ms (Reachable)" },
                    onFailure = { "Failed: ${it.message ?: "Unreachable"}" }
                )
                state.copy(
                    testingNodeId = null,
                    nodeLatencyResults = state.nodeLatencyResults + (node.id to text)
                )
            }
        }
    }

    fun connectRoutingNode(node: RoutingNode) {
        RoutingManager.connectNode(getApplication(), node, _uiState.value.routingState.useVpnTunnel)
    }

    fun disconnectRouting() {
        RoutingManager.disconnect(getApplication())
    }
}
