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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
     * Preserves prior selections if the user is reopening the filter for an existing playlist.
     */
    suspend fun fetchCategoriesForFilter(source: PlaylistSource): List<ContentCategory> {
        val existingSelections = dao.getCategoriesForSource(source.id)
            .associate { it.id to it.isSelected }

        val fresh = when (source.type) {
            SourceType.STALKER -> stalkerService.fetchAllCategories(source)
            SourceType.XTREAM,
            SourceType.M3U_URL,
            SourceType.M3U_FILE,
            SourceType.GIST_JSON -> xtreamAndM3uService.fetchCategories(source)
        }

        val merged = fresh.map { category ->
            category.copy(isSelected = existingSelections[category.id] ?: false)
        }

        dao.upsertPlaylist(source.toEntity())
        dao.deleteCategoriesForSource(source.id)
        dao.upsertCategories(merged.map { it.toEntity() })
        return merged
    }

    suspend fun getSavedCategoriesForSource(sourceId: String): List<ContentCategory> {
        return dao.getCategoriesForSource(sourceId).map { it.toModel() }
    }

    /**
     * Step 2: Saves checked categories and imports ONLY media items inside checked categories.
     * Unchecked categories are excluded completely from import; existing favorites & resume positions
     * are preserved across re-imports.
     */
    suspend fun importSelectedCategories(
        source: PlaylistSource,
        updatedCategories: List<ContentCategory>
    ): Int {
        val selectedOnly = updatedCategories.filter { it.isSelected }

        val existingItems = dao.getMediaItemsForSource(source.id).associateBy { it.id }

        val importedItems = when (source.type) {
            SourceType.STALKER -> stalkerService.fetchItemsForCategories(source, selectedOnly)
            SourceType.XTREAM,
            SourceType.M3U_URL,
            SourceType.M3U_FILE,
            SourceType.GIST_JSON -> xtreamAndM3uService.fetchItemsForCategories(source, selectedOnly)
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

        val countsByCat = importedItems.groupingBy { it.categoryId }.eachCount()
        val finalizedCategories = updatedCategories.map { cat ->
            cat.copy(itemCount = countsByCat[cat.id] ?: if (cat.isSelected) 0 else cat.itemCount)
        }

        dao.upsertPlaylist(source.copy(lastSyncedAt = System.currentTimeMillis(), isActive = true).toEntity())
        dao.setSingleActivePlaylist(source.id)
        dao.deleteCategoriesForSource(source.id)
        dao.upsertCategories(finalizedCategories.map { it.toEntity() })
        dao.deleteMediaItemsForSource(source.id)
        if (importedItems.isNotEmpty()) {
            dao.upsertMediaItems(importedItems.map { it.toEntity() })
        }
        return importedItems.size
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
