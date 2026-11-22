package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.ProxyProtocol
import com.example.models.RoutingNode
import com.example.models.SourceType
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity(tableName = "playlist_sources")
data class PlaylistSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val portalUrl: String,
    val macAddress: String,
    val serialNumber: String,
    val deviceId1: String,
    val deviceId2: String,
    val signature: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
    val localFileUri: String,
    val epgUrl: String,
    val lastSyncedAt: Long,
    val isActive: Boolean
)

@Entity(tableName = "content_categories", primaryKeys = ["sourceId", "id"])
data class CategoryEntity(
    val id: String,
    val sourceId: String,
    val name: String,
    val countryGroup: String,
    val type: String,
    val isSelected: Boolean,
    val itemCount: Int
)

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val streamUrlOrCmd: String,
    val posterUrl: String,
    val backdropUrl: String,
    val categoryId: String,
    val type: String,
    val rating: String,
    val plot: String,
    val year: String,
    val epgChannelId: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val channelNumber: Int,
    val isFavorite: Boolean,
    val resumePositionMs: Long,
    val durationMs: Long,
    val streamHeadersJson: String
)

@Entity(tableName = "routing_nodes")
data class RoutingNodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val countryCode: String,
    val countryName: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val username: String,
    val password: String,
    val isEnabled: Boolean
)

@Dao
interface IptvDao {
    @Query("SELECT * FROM playlist_sources ORDER BY lastSyncedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistSourceEntity>>

    @Query("SELECT * FROM playlist_sources WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: PlaylistSourceEntity)

    @Query("DELETE FROM playlist_sources WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM content_categories WHERE sourceId = :sourceId ORDER BY countryGroup, name")
    suspend fun getCategoriesForSource(sourceId: String): List<CategoryEntity>

    @Query("SELECT * FROM content_categories WHERE isSelected = 1 ORDER BY countryGroup, name")
    fun observeSelectedCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM content_categories ORDER BY countryGroup, name")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM content_categories WHERE sourceId = :sourceId")
    suspend fun deleteCategoriesForSource(sourceId: String)

    @Query("SELECT * FROM media_items ORDER BY channelNumber ASC, title ASC")
    fun observeAllMediaItems(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE sourceId = :sourceId")
    suspend fun getMediaItemsForSource(sourceId: String): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItems(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE sourceId = :sourceId")
    suspend fun deleteMediaItemsForSource(sourceId: String)

    @Query("UPDATE media_items SET isFavorite = :isFavorite WHERE id = :itemId")
    suspend fun updateFavorite(itemId: String, isFavorite: Boolean)

    @Query("UPDATE media_items SET resumePositionMs = :positionMs, durationMs = :durationMs WHERE id = :itemId")
    suspend fun updateResumePosition(itemId: String, positionMs: Long, durationMs: Long)

    @Query("SELECT * FROM routing_nodes ORDER BY countryName ASC, name ASC")
    fun observeRoutingNodes(): Flow<List<RoutingNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutingNode(node: RoutingNodeEntity)

    @Query("DELETE FROM routing_nodes WHERE id = :id")
    suspend fun deleteRoutingNode(id: String)
}

@Database(
    entities = [
        PlaylistSourceEntity::class,
        CategoryEntity::class,
        MediaItemEntity::class,
        RoutingNodeEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun iptvDao(): IptvDao

    companion object {
        @Volatile
        private var instance: IptvDatabase? = null

        fun getInstance(context: Context): IptvDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IptvDatabase::class.java,
                    "tiviwave_iptv.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

fun PlaylistSource.toEntity(): PlaylistSourceEntity = PlaylistSourceEntity(
    id = id,
    name = name,
    type = type.name,
    portalUrl = portalUrl,
    macAddress = macAddress,
    serialNumber = serialNumber,
    deviceId1 = deviceId1,
    deviceId2 = deviceId2,
    signature = signature,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    localFileUri = localFileUri,
    epgUrl = epgUrl,
    lastSyncedAt = lastSyncedAt,
    isActive = isActive
)

fun PlaylistSourceEntity.toModel(): PlaylistSource = PlaylistSource(
    id = id,
    name = name,
    type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.M3U_URL),
    portalUrl = portalUrl,
    macAddress = macAddress,
    serialNumber = serialNumber,
    deviceId1 = deviceId1,
    deviceId2 = deviceId2,
    signature = signature,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    localFileUri = localFileUri,
    epgUrl = epgUrl,
    lastSyncedAt = lastSyncedAt,
    isActive = isActive
)

fun ContentCategory.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    sourceId = sourceId,
    name = name,
    countryGroup = countryGroup,
    type = type.name,
    isSelected = isSelected,
    itemCount = itemCount
)

fun CategoryEntity.toModel(): ContentCategory = ContentCategory(
    id = id,
    sourceId = sourceId,
    name = name,
    countryGroup = countryGroup,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.LIVE),
    isSelected = isSelected,
    itemCount = itemCount
)

fun MediaItem.toEntity(): MediaItemEntity {
    val json = JSONObject()
    streamHeaders.forEach { (k, v) -> json.put(k, v) }
    return MediaItemEntity(
        id = id,
        sourceId = sourceId,
        title = title,
        streamUrlOrCmd = streamUrlOrCmd,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        categoryId = categoryId,
        type = type.name,
        rating = rating,
        plot = plot,
        year = year,
        epgChannelId = epgChannelId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        channelNumber = channelNumber,
        isFavorite = isFavorite,
        resumePositionMs = resumePositionMs,
        durationMs = durationMs,
        streamHeadersJson = json.toString()
    )
}

fun MediaItemEntity.toModel(): MediaItem {
    val headers = mutableMapOf<String, String>()
    runCatching {
        val obj = JSONObject(streamHeadersJson)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            headers[k] = obj.optString(k)
        }
    }
    return MediaItem(
        id = id,
        sourceId = sourceId,
        title = title,
        streamUrlOrCmd = streamUrlOrCmd,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        categoryId = categoryId,
        type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.LIVE),
        rating = rating,
        plot = plot,
        year = year,
        epgChannelId = epgChannelId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        channelNumber = channelNumber,
        isFavorite = isFavorite,
        resumePositionMs = resumePositionMs,
        durationMs = durationMs,
        streamHeaders = headers
    )
}

fun RoutingNode.toEntity(): RoutingNodeEntity = RoutingNodeEntity(
    id = id,
    name = name,
    countryCode = countryCode,
    countryName = countryName,
    host = host,
    port = port,
    protocol = protocol.name,
    username = username,
    password = password,
    isEnabled = isEnabled
)

fun RoutingNodeEntity.toModel(): RoutingNode = RoutingNode(
    id = id,
    name = name,
    countryCode = countryCode,
    countryName = countryName,
    host = host,
    port = port,
    protocol = runCatching { ProxyProtocol.valueOf(protocol) }.getOrDefault(ProxyProtocol.HTTP),
    username = username,
    password = password,
    isEnabled = isEnabled
)
