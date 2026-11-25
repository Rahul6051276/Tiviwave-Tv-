package com.example.data

import android.content.Context
import com.example.models.ContentCategory
import com.example.models.EpgProgram
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.ResolvedStream
import com.example.models.RoutingNode
import com.example.models.SeasonGroup
import com.example.models.SourceType
import com.example.services.EpgParser
import com.example.services.StalkerService
import com.example.services.XtreamAndM3uService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CategoryFetchResult(
    val sources: List<PlaylistSource>,
    val categories: List<ContentCategory>,
    val summaryMessage: String? = null
)

class IptvRepository(context: Context) {

    private val dao = IptvDatabase.getInstance(context).iptvDao()
    private val stalkerService = StalkerService()
    private val xtreamAndM3uService = XtreamAndM3uService(context.applicationContext)
    private val epgParser = EpgParser()

    val playlistsFlow: Flow<List<PlaylistSource>> =
        dao.observePlaylists().map { list -> list.map { it.toModel() } }

    val selectedCategoriesFlow: Flow<List<ContentCategory>> =
        dao.observeSelectedCategories().map { list -> list.map { it.toModel() } }

    val allCategoriesFlow: Flow<List<ContentCategory>> =
        dao.observeAllCategories().map { list -> list.map { it.toModel() } }

    val mediaItemsFlow: Flow<List<MediaItem>> =
        dao.observeAllMediaItems().map { list -> list.map { it.toModel() } }

    val routingNodesFlow: Flow<List<RoutingNode>> =
        dao.observeRoutingNodes().map { list -> list.map { it.toModel() } }

    suspend fun savePlaylistMetadata(source: PlaylistSource) {
        dao.upsertPlaylist(source.toEntity())
    }

    /**
     * Switches the active playlist without deleting or altering any other saved playlists.
     * Pass null to enable "All Playlists (Combined)" mode.
     */
    suspend fun setActivePlaylist(sourceId: String?) {
        if (sourceId == null) {
            dao.setAllPlaylistsActive()
        } else {
            dao.setSingleActivePlaylist(sourceId)
        }
    }

    suspend fun deletePlaylist(sourceId: String) {
        dao.deleteMediaItemsForSource(sourceId)
        dao.deleteCategoriesForSource(sourceId)
        dao.deletePlaylist(sourceId)
        val remaining = dao.getAllPlaylists()
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            dao.setSingleActivePlaylist(remaining.first().id)
        }
    }

    /**
     * Step 1: Metadata-only category fetch for the 2-Step Country & Category Pre-Load Filter.
     * Automatically detects Multi-Provider Gist JSON manifests (e.g. `providers.json` containing
     * multiple Xtream / Stalker / M3U accounts) and loads ALL IPTV providers in parallel.
     */
    suspend fun fetchCategoriesForFilter(source: PlaylistSource): CategoryFetchResult {
        if (source.type == SourceType.GIST_JSON) {
            val manifest = xtreamAndM3uService.resolveGistProvidersIfManifest(source)
            if (manifest != null) {
                if (manifest.providers.isEmpty()) {
                    val skippedInfo = manifest.skippedUnsupportedNames.joinToString(", ")
                    error(
                        if (skippedInfo.isNotBlank()) {
                            "Gist manifest contained no Xtream/Stalker/M3U IPTV providers (skipped: $skippedInfo)."
                        } else {
                            "Gist manifest contained no valid IPTV providers."
                        }
                    )
                }

                val perProviderResults = coroutineScope {
                    manifest.providers.map { provider ->
                        async {
                            runCatching {
                                val existingSelections = dao.getCategoriesForSource(provider.id)
                                    .associate { it.id to it.isSelected }
                                val fresh = fetchRawCategoriesForSingleSource(provider)
                                val merged = fresh.map { cat ->
                                    cat.copy(isSelected = existingSelections[cat.id] ?: false)
                                }
                                dao.upsertPlaylist(provider.toEntity())
                                dao.deleteCategoriesForSource(provider.id)
                                dao.upsertCategories(merged.map { it.toEntity() })
                                provider to merged
                            }
                        }
                    }.awaitAll()
                }

                val loadedSources = mutableListOf<PlaylistSource>()
                val allCategories = mutableListOf<ContentCategory>()
                val failedNotes = mutableListOf<String>()

                perProviderResults.forEachIndexed { idx, res ->
                    val provider = manifest.providers[idx]
                    res.onSuccess { (src, cats) ->
                        if (cats.isNotEmpty()) {
                            loadedSources += src
                            allCategories += cats
                        } else {
                            failedNotes += "${provider.name}: 0 categories"
                        }
                    }.onFailure { err ->
                        failedNotes += "${provider.name}: ${err.message ?: "connection failed"}"
                    }
                }

                if (allCategories.isEmpty()) {
                    error("Gist providers failed to load: ${failedNotes.joinToString(" | ")}")
                }

                val loadedNames = loadedSources.joinToString(", ") { it.name }
                val summary = buildString {
                    append("Loaded ${allCategories.size} categories from ${loadedSources.size} IPTV provider(s): $loadedNames.")
                    if (failedNotes.isNotEmpty()) {
                        append(" Failed: ${failedNotes.joinToString("; ")}.")
                    }
                }
                return CategoryFetchResult(
                    sources = loadedSources,
                    categories = allCategories,
                    summaryMessage = summary
                )
            }
        }

        val existingSelections = dao.getCategoriesForSource(source.id)
            .associate { it.id to it.isSelected }

        val fresh = fetchRawCategoriesForSingleSource(source)

        val merged = fresh.map { category ->
            category.copy(isSelected = existingSelections[category.id] ?: false)
        }

        dao.upsertPlaylist(source.toEntity())
        dao.deleteCategoriesForSource(source.id)
        dao.upsertCategories(merged.map { it.toEntity() })
        return CategoryFetchResult(
            sources = listOf(source),
            categories = merged,
            summaryMessage = "Loaded ${merged.size} categories. Select countries & categories to import."
        )
    }

    private suspend fun fetchRawCategoriesForSingleSource(source: PlaylistSource): List<ContentCategory> {
        return when (source.type) {
            SourceType.STALKER -> stalkerService.fetchAllCategories(source)
            SourceType.XTREAM,
            SourceType.M3U_URL,
            SourceType.M3U_FILE,
            SourceType.GIST_JSON -> xtreamAndM3uService.fetchCategories(source)
        }
    }

    suspend fun getSavedCategoriesForSource(sourceId: String): List<ContentCategory> {
        return dao.getCategoriesForSource(sourceId).map { it.toModel() }
    }

    /**
     * Step 2: Saves checked categories and imports ONLY media items inside checked categories
     * across one or multiple providers (e.g. from a Multi-Provider Gist manifest).
     * Returns `(totalImportedItems, recommendedActivePlaylistId)` where `null` activates Combined mode.
     */
    suspend fun importSelectedCategories(
        sources: List<PlaylistSource>,
        updatedCategories: List<ContentCategory>
    ): Pair<Int, String?> {
        val categoriesBySource = updatedCategories.groupBy { it.sourceId }
        val importedSourceIds = mutableListOf<String>()
        var totalImported = 0

        val providerImports = coroutineScope {
            sources.map { provider ->
                async {
                    val providerAllCats = categoriesBySource[provider.id].orEmpty()
                    val selectedOnly = providerAllCats.filter { it.isSelected }
                    val existingItems = dao.getMediaItemsForSource(provider.id).associateBy { it.id }

                    val importedItems = if (selectedOnly.isNotEmpty()) {
                        when (provider.type) {
                            SourceType.STALKER -> stalkerService.fetchItemsForCategories(provider, selectedOnly)
                            SourceType.XTREAM,
                            SourceType.M3U_URL,
                            SourceType.M3U_FILE,
                            SourceType.GIST_JSON -> xtreamAndM3uService.fetchItemsForCategories(provider, selectedOnly)
                        }.map { item ->
                            val prev = existingItems[item.id]
                            if (prev != null) {
                                item.copy(
                                    isFavorite = prev.isFavorite,
                                    resumePositionMs = prev.resumePositionMs,
                                    durationMs = prev.durationMs
                                )
                            } else {
                                item
                            }
                        }
                    } else {
                        emptyList()
                    }
                    Triple(provider, providerAllCats, importedItems)
                }
            }.awaitAll()
        }

        for ((provider, providerAllCats, importedItems) in providerImports) {
            val hasSelected = providerAllCats.any { it.isSelected }
            val countsByCat = importedItems.groupingBy { it.categoryId }.eachCount()
            val finalizedCategories = providerAllCats.map { cat ->
                cat.copy(itemCount = countsByCat[cat.id] ?: if (cat.isSelected) 0 else cat.itemCount)
            }

            dao.upsertPlaylist(
                provider.copy(
                    lastSyncedAt = if (hasSelected) System.currentTimeMillis() else provider.lastSyncedAt,
                    isActive = true
                ).toEntity()
            )
            if (finalizedCategories.isNotEmpty()) {
                dao.deleteCategoriesForSource(provider.id)
                dao.upsertCategories(finalizedCategories.map { it.toEntity() })
            }
            if (hasSelected) {
                dao.deleteMediaItemsForSource(provider.id)
                if (importedItems.isNotEmpty()) {
                    dao.upsertMediaItems(importedItems.map { it.toEntity() })
                }
                importedSourceIds += provider.id
                totalImported += importedItems.size
            }
        }

        val activeId: String? = when {
            importedSourceIds.size > 1 -> {
                dao.setAllPlaylistsActive()
                null
            }
            importedSourceIds.size == 1 -> {
                dao.setSingleActivePlaylist(importedSourceIds.first())
                importedSourceIds.first()
            }
            sources.size == 1 -> {
                dao.setSingleActivePlaylist(sources.first().id)
                sources.first().id
            }
            else -> {
                dao.setAllPlaylistsActive()
                null
            }
        }

        return totalImported to activeId
    }

    suspend fun resolvePlayableStream(
        item: MediaItem,
        forceRefresh: Boolean = false
    ): ResolvedStream {
        val source = dao.getPlaylistById(item.sourceId)?.toModel()
        if (source != null && source.type == SourceType.STALKER) {
            return stalkerService.resolveStreamLink(source, item, forceRefresh)
        }
        return ResolvedStream(
            url = item.streamUrlOrCmd.trim(),
            headers = item.streamHeaders
        )
    }

    suspend fun fetchSeriesSeasons(seriesItem: MediaItem): List<SeasonGroup> {
        val source = dao.getPlaylistById(seriesItem.sourceId)?.toModel() ?: return emptyList()
        return when (source.type) {
            SourceType.STALKER -> stalkerService.fetchSeriesSeasons(source, seriesItem)
            SourceType.XTREAM -> xtreamAndM3uService.fetchXtreamSeriesSeasons(source, seriesItem)
            SourceType.M3U_URL,
            SourceType.M3U_FILE,
            SourceType.GIST_JSON -> {
                val allEpisodes = dao.getMediaItemsForSource(source.id)
                    .map { it.toModel() }
                    .filter {
                        it.id == seriesItem.id ||
                            (seriesItem.seriesId.isNotBlank() && it.seriesId == seriesItem.seriesId)
                    }
                allEpisodes.groupBy { it.seasonNumber.coerceAtLeast(1) }
                    .entries
                    .sortedBy { it.key }
                    .map { (seasonNum, eps) ->
                        SeasonGroup(
                            seasonNumber = seasonNum,
                            title = "Season $seasonNum",
                            episodes = eps.sortedBy { it.episodeNumber }
                        )
                    }
            }
        }
    }

    suspend fun fetchEpgForPlaylist(
        source: PlaylistSource,
        channelIds: Set<String>
    ): Map<String, List<EpgProgram>> {
        val url = when {
            source.epgUrl.isNotBlank() -> source.epgUrl
            source.type == SourceType.XTREAM -> xtreamAndM3uService.buildXtreamXmltvUrl(source)
            else -> ""
        }
        if (url.isBlank()) return emptyMap()
        return epgParser.fetchAndParseXmltv(url, channelIds)
    }

    suspend fun fetchStalkerShortEpg(item: MediaItem): List<EpgProgram> {
        val source = dao.getPlaylistById(item.sourceId)?.toModel() ?: return emptyList()
        if (source.type != SourceType.STALKER) return emptyList()
        return stalkerService.fetchShortEpg(source, item.epgChannelId)
    }

    suspend fun toggleFavorite(itemId: String, isFavorite: Boolean) {
        dao.updateFavorite(itemId, isFavorite)
    }

    suspend fun saveResumePosition(itemId: String, positionMs: Long, durationMs: Long) {
        dao.updateResumePosition(itemId, positionMs, durationMs)
    }

    suspend fun upsertRoutingNode(node: RoutingNode) {
        dao.upsertRoutingNode(node.toEntity())
    }

    suspend fun deleteRoutingNode(nodeId: String) {
        dao.deleteRoutingNode(nodeId)
    }
}
