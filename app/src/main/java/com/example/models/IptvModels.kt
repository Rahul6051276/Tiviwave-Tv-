package com.example.models

import java.util.UUID

enum class SourceType(val displayName: String) {
    STALKER("Stalker Portal (MAG/STB)"),
    XTREAM("Xtream Codes API"),
    M3U_URL("M3U / M3U8 URL"),
    M3U_FILE("Local M3U File"),
    GIST_JSON("GitHub Gist JSON")
}

enum class ContentType(val label: String) {
    LIVE("Live TV"),
    VOD("Movies (VOD)"),
    SERIES("Web Series")
}

enum class ProxyProtocol(val label: String) {
    HTTP("HTTP"),
    SOCKS5("SOCKS5")
}

enum class AspectRatioMode(val label: String) {
    FIT("FIT"),
    FILL("FILL"),
    ZOOM("ZOOM"),
    RATIO_16_9("16:9")
}

enum class TvSection(val label: String) {
    LIVE_TV("Live TV"),
    MOVIES("Movies"),
    WEB_SERIES("Web Series"),
    FAVORITES("Favorites"),
    ROUTING("Country / Proxy"),
    PLAYLISTS("Playlists & Filter")
}

enum class CatalogSortMode(val label: String) {
    LATEST_ADDED("Latest Added"),
    RELEASE_YEAR("Release Year (Newest)"),
    RATING("Rating"),
    TITLE_AZ("A–Z")
}

data class PlaylistSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: SourceType,
    val portalUrl: String = "",
    val macAddress: String = "",
    val serialNumber: String = "",
    val deviceId1: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val localFileUri: String = "",
    val epgUrl: String = "",
    val lastSyncedAt: Long = 0L,
    val isActive: Boolean = true
)

data class ContentCategory(
    val id: String,
    val sourceId: String,
    val name: String,
    val countryGroup: String,
    val type: ContentType,
    val isSelected: Boolean = false,
    val itemCount: Int = 0
)

data class MediaItem(
    val id: String,
    val sourceId: String = "",
    val title: String,
    val streamUrlOrCmd: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val categoryId: String,
    val type: ContentType,
    val rating: String = "",
    val plot: String = "",
    val year: String = "",
    val epgChannelId: String = "",
    val seriesId: String = "",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val channelNumber: Int = 0,
    val isFavorite: Boolean = false,
    val resumePositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val addedAt: Long = 0L,
    val streamHeaders: Map<String, String> = emptyMap()
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTimeMs: Long,
    val endTimeMs: Long
) {
    fun progressAt(nowMs: Long): Float {
        val total = (endTimeMs - startTimeMs).coerceAtLeast(1L)
        return ((nowMs - startTimeMs).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun isCurrentAt(nowMs: Long): Boolean = nowMs in startTimeMs..endTimeMs
}

data class RoutingNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val countryCode: String,
    val countryName: String,
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol = ProxyProtocol.HTTP,
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = false
)

data class RoutingState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val useVpnTunnel: Boolean = true,
    val activeNode: RoutingNode? = null,
    val statusMessage: String = "Direct Connection (No Proxy)",
    val lastLatencyMs: Long? = null
)

data class StreamTechInfo(
    val resolution: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val bitrateKbps: Int = 0,
    val frameRate: String = ""
)

data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String = "",
    val isSelected: Boolean = false
)

data class SeasonGroup(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<MediaItem>
)

data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)
