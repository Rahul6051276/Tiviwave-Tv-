package com.example.services

import android.content.Context
import android.net.Uri
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.SeasonGroup
import com.example.models.SourceType
import com.example.routing.RoutingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class XtreamAndM3uService(private val context: Context) {

    // Temporary in-memory parse cache for M3U / Gist sources between Step 1 (Categories) and Step 2 (Import)
    private val parsedItemBuffer = ConcurrentHashMap<String, List<MediaItem>>()

    /**
     * Step 1 Metadata-Only Fetch for Xtream Codes, M3U URL, Local M3U File, and GitHub Gist JSON.
     */
    suspend fun fetchCategories(source: PlaylistSource): List<ContentCategory> = when (source.type) {
        SourceType.XTREAM -> fetchXtreamCategories(source)
        SourceType.M3U_URL -> fetchM3uCategoriesFromUrl(source)
        SourceType.M3U_FILE -> fetchM3uCategoriesFromLocalFile(source)
        SourceType.GIST_JSON -> fetchGistJsonCategories(source)
        SourceType.STALKER -> emptyList()
    }

    /**
     * Step 2 Selective Import: Loads ONLY items belonging to checked categories.
     * Sorts VOD and Web Series by `added` / `last_modified` timestamp (or numeric ID descending)
     * so newly uploaded IPTV releases always appear at the top.
     */
    suspend fun fetchItemsForCategories(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> {
        if (selectedCategories.isEmpty()) return emptyList()
        return when (source.type) {
            SourceType.XTREAM -> fetchXtreamItemsForCategories(source, selectedCategories)
            SourceType.M3U_URL,
            SourceType.M3U_FILE -> filterBufferedOrReloadM3u(source, selectedCategories)
            SourceType.GIST_JSON -> filterBufferedOrReloadGist(source, selectedCategories)
            SourceType.STALKER -> emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // XTREAM CODES API
    // -------------------------------------------------------------------------

    private fun normalizeBaseUrl(url: String): String {
        var clean = url.trim().trimEnd('/')
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            clean = "http://$clean"
        }
        return clean.removeSuffix("/player_api.php")
    }

    private fun extractOrigin(baseUrl: String): String {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val defaultPort = (parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        return if (defaultPort) {
            "${parsed.scheme}://${parsed.host}"
        } else {
            "${parsed.scheme}://${parsed.host}:${parsed.port}"
        }
    }

    private fun resolveXtreamAssetUrl(baseUrl: String, assetPath: String): String {
        val clean = assetPath.trim()
        if (clean.isBlank() || clean.equals("null", ignoreCase = true) || clean.equals("false", ignoreCase = true)) {
            return ""
        }
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        if (clean.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("https://", ignoreCase = true)) "https:" else "http:"
            return "$scheme$clean"
        }
        if (clean.startsWith("/")) {
            return "${extractOrigin(baseUrl)}$clean"
        }
        return "${baseUrl.trimEnd('/')}/$clean"
    }

    fun buildXtreamXmltvUrl(source: PlaylistSource): String {
        val base = normalizeBaseUrl(source.portalUrl)
        return "$base/xmltv.php?username=${source.username}&password=${source.password}"
    }

    private fun executeXtreamApi(source: PlaylistSource, action: String, extra: Map<String, String> = emptyMap()): String {
        val base = normalizeBaseUrl(source.portalUrl)
        val urlBuilder = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: error("Invalid Xtream portal URL: $base")
        urlBuilder.addQueryParameter("username", source.username.trim())
        urlBuilder.addQueryParameter("password", source.password.trim())
        urlBuilder.addQueryParameter("action", action)
        extra.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val client = RoutingManager.buildHttpClient(
            connectTimeoutSec = 8L,
            readTimeoutSec = 12L
        )
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "TiviWaveTV/1.0 (AndroidTV)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Xtream API HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun fetchXtreamCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<ContentCategory>()

        val specs = listOf(
            "get_live_categories" to ContentType.LIVE,
            "get_vod_categories" to ContentType.VOD,
            "get_series_categories" to ContentType.SERIES
        )

        for ((action, contentType) in specs) {
            runCatching {
                val raw = executeXtreamApi(source, action).trim()
                if (!raw.startsWith("[")) return@runCatching
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val catId = obj.optCleanString("category_id")
                    val catName = obj.optCleanString("category_name")
                    if (catId.isBlank() || catName.isBlank()) continue
                    categories += ContentCategory(
                        id = "${contentType.name.lowercase()}_$catId",
                        sourceId = source.id,
                        name = catName,
                        countryGroup = CountryCategoryClassifier.classifyCountryGroup(catName),
                        type = contentType,
                        isSelected = false
                    )
                }
            }
        }
        categories.distinctBy { "${it.type.name}:${it.id}" }
    }

    private suspend fun fetchXtreamItemsForCategories(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(source.portalUrl)
        val user = source.username.trim()
        val pass = source.password.trim()
        val items = mutableListOf<MediaItem>()

        for (category in selectedCategories) {
            val rawCatId = category.id.substringAfter('_', category.id)
            val action = when (category.type) {
                ContentType.LIVE -> "get_live_streams"
                ContentType.VOD -> "get_vod_streams"
                ContentType.SERIES -> "get_series"
            }

            runCatching {
                val raw = executeXtreamApi(source, action, mapOf("category_id" to rawCatId)).trim()
                if (!raw.startsWith("[")) return@runCatching
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    when (category.type) {
                        ContentType.LIVE -> {
                            val streamId = obj.optCleanString("stream_id")
                            val name = obj.optCleanString("name")
                            if (streamId.isBlank() || name.isBlank()) continue
                            val iconUrl = resolveXtreamAssetUrl(base, obj.optCleanString("stream_icon"))
                            items += MediaItem(
                                id = "${source.id}_live_$streamId",
                                sourceId = source.id,
                                title = name,
                                streamUrlOrCmd = "$base/live/$user/$pass/$streamId.ts",
                                posterUrl = iconUrl,
                                categoryId = category.id,
                                type = ContentType.LIVE,
                                epgChannelId = obj.optCleanString("epg_channel_id").ifBlank { streamId },
                                channelNumber = obj.optInt("num", i + 1)
                            )
                        }

                        ContentType.VOD -> {
                            val streamId = obj.optCleanString("stream_id")
                            val name = obj.optCleanString("name")
                            val ext = obj.optCleanString("container_extension").ifBlank { "mp4" }
                            if (streamId.isBlank() || name.isBlank()) continue
                            val poster = resolveXtreamAssetUrl(
                                base,
                                obj.optCleanString("stream_icon").ifBlank { obj.optCleanString("cover") }
                            )
                            val addedAt = parseXtreamAddedMs(
                                obj.optCleanString("added"),
                                fallbackId = streamId.toLongOrNull() ?: i.toLong()
                            )
                            val rating = normalizeRating(
                                obj.optCleanString("rating").ifBlank { obj.optCleanString("rating_5based") }
                            )
                            items += MediaItem(
                                id = "${source.id}_vod_$streamId",
                                sourceId = source.id,
                                title = name,
                                streamUrlOrCmd = "$base/movie/$user/$pass/$streamId.$ext",
                                posterUrl = poster,
                                backdropUrl = poster,
                                categoryId = category.id,
                                type = ContentType.VOD,
                                rating = rating,
                                year = obj.optCleanString("year").ifBlank { extractYearFromTitle(name) },
                                plot = obj.optCleanString("plot"),
                                addedAt = addedAt
                            )
                        }

                        ContentType.SERIES -> {
                            val seriesId = obj.optCleanString("series_id")
                            val name = obj.optCleanString("name")
                            if (seriesId.isBlank() || name.isBlank()) continue
                            val rawBackdrop = obj.optJSONArray("backdrop_path")?.optString(0).orEmpty()
                            val rawCover = obj.optCleanString("cover")
                                .ifBlank { obj.optCleanString("stream_icon") }
                                .ifBlank { obj.optCleanString("poster") }
                                .ifBlank { rawBackdrop }
                            val poster = resolveXtreamAssetUrl(base, rawCover)
                            val backdrop = resolveXtreamAssetUrl(base, rawBackdrop.ifBlank { rawCover })
                            val addedAt = parseXtreamAddedMs(
                                obj.optCleanString("last_modified").ifBlank { obj.optCleanString("added") },
                                fallbackId = seriesId.toLongOrNull() ?: i.toLong()
                            )
                            val rating = normalizeRating(
                                obj.optCleanString("rating").ifBlank { obj.optCleanString("rating_5based") }
                            )
                            items += MediaItem(
                                id = "${source.id}_series_$seriesId",
                                sourceId = source.id,
                                title = name,
                                streamUrlOrCmd = "",
                                posterUrl = poster,
                                backdropUrl = backdrop,
                                categoryId = category.id,
                                type = ContentType.SERIES,
                                rating = rating,
                                plot = obj.optCleanString("plot"),
                                year = obj.optCleanString("releaseDate").take(4)
                                    .ifBlank { obj.optCleanString("year") }
                                    .ifBlank { extractYearFromTitle(name) },
                                seriesId = seriesId,
                                addedAt = addedAt
                            )
                        }
                    }
                }
            }
        }

        val deduped = items.distinctBy { "${it.type.name}:${it.id}" }
        val liveItems = deduped.filter { it.type == ContentType.LIVE }
            .sortedWith(compareBy<MediaItem> { if (it.channelNumber > 0) it.channelNumber else Int.MAX_VALUE }.thenBy { it.title })
        val vodAndSeries = deduped.filter { it.type != ContentType.LIVE }
            .sortedWith(
                compareByDescending<MediaItem> { it.addedAt }
                    .thenByDescending { it.id.substringAfterLast('_').filter(Char::isDigit).toLongOrNull() ?: 0L }
            )
        liveItems + vodAndSeries
    }

    suspend fun fetchXtreamSeriesSeasons(
        source: PlaylistSource,
        seriesItem: MediaItem
    ): List<SeasonGroup> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(source.portalUrl)
        val user = source.username.trim()
        val pass = source.password.trim()
        val seriesId = seriesItem.seriesId.ifBlank { seriesItem.id.substringAfterLast('_') }

        val raw = executeXtreamApi(source, "get_series_info", mapOf("series_id" to seriesId))
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext emptyList()
        val episodesObj = root.optJSONObject("episodes") ?: return@withContext emptyList()

        val seasonGroups = mutableListOf<SeasonGroup>()
        val keys = episodesObj.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val seasonNum = seasonKey.toIntOrNull() ?: 1
            val epArray = episodesObj.optJSONArray(seasonKey) ?: continue
            val episodes = mutableListOf<MediaItem>()

            for (i in 0 until epArray.length()) {
                val epObj = epArray.optJSONObject(i) ?: continue
                val epId = epObj.optCleanString("id")
                if (epId.isBlank()) continue
                val ext = epObj.optCleanString("container_extension").ifBlank { "mp4" }
                val epNum = epObj.optInt("episode_num", i + 1)
                val info = epObj.optJSONObject("info")
                val title = epObj.optCleanString("title").ifBlank { "Episode $epNum" }
                val plot = info?.optCleanString("plot").orEmpty().ifBlank { seriesItem.plot }
                val rawPoster = info?.optCleanString("movie_image").orEmpty()
                val poster = resolveXtreamAssetUrl(base, rawPoster).ifBlank { seriesItem.posterUrl }

                episodes += MediaItem(
                    id = "${source.id}_xep_${seriesId}_${seasonNum}_$epId",
                    sourceId = source.id,
                    title = title,
                    streamUrlOrCmd = "$base/series/$user/$pass/$epId.$ext",
                    posterUrl = poster,
                    backdropUrl = seriesItem.backdropUrl,
                    categoryId = seriesItem.categoryId,
                    type = ContentType.SERIES,
                    rating = normalizeRating(info?.optCleanString("rating").orEmpty()).ifBlank { seriesItem.rating },
                    plot = plot,
                    year = seriesItem.year,
                    seriesId = seriesId,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    addedAt = seriesItem.addedAt
                )
            }

            if (episodes.isNotEmpty()) {
                seasonGroups += SeasonGroup(
                    seasonNumber = seasonNum,
                    title = "Season $seasonNum",
                    episodes = episodes.sortedBy { it.episodeNumber }
                )
            }
        }
        seasonGroups.sortedBy { it.seasonNumber }
    }

    // -------------------------------------------------------------------------
    // M3U / M3U8 (REMOTE URL & LOCAL FILE)
    // -------------------------------------------------------------------------

    private suspend fun fetchM3uCategoriesFromUrl(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val text = downloadText(source.m3uUrl)
        val (categories, items) = parseM3uContent(source.id, text)
        parsedItemBuffer[source.id] = items
        categories
    }

    private suspend fun fetchM3uCategoriesFromLocalFile(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source.localFileUri)
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        } ?: error("Unable to read local M3U file")
        val (categories, items) = parseM3uContent(source.id, text)
        parsedItemBuffer[source.id] = items
        categories
    }

    private suspend fun filterBufferedOrReloadM3u(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> {
        val cached = parsedItemBuffer[source.id] ?: run {
            if (source.type == SourceType.M3U_URL) {
                fetchM3uCategoriesFromUrl(source)
            } else {
                fetchM3uCategoriesFromLocalFile(source)
            }
            parsedItemBuffer[source.id].orEmpty()
        }
        val selectedIds = selectedCategories.map { it.id }.toSet()
        val filtered = cached.filter { it.categoryId in selectedIds }
        val live = filtered.filter { it.type == ContentType.LIVE }
        val vodAndSeries = filtered.filter { it.type != ContentType.LIVE }
            .sortedByDescending { it.addedAt }
        return live + vodAndSeries
    }

    fun parseM3uContent(sourceId: String, content: String): Pair<List<ContentCategory>, List<MediaItem>> {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val items = mutableListOf<MediaItem>()
        val categoryMap = linkedMapOf<String, ContentCategory>()
        val categoryCounts = mutableMapOf<String, Int>()

        var pendingExtInf: String? = null
        val pendingHeaders = mutableMapOf<String, String>()
        var channelCounter = 1
        var sequenceOrder = 1L

        val attrRegex = Regex("([a-zA-Z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingExtInf = line
                    pendingHeaders.clear()
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter("#EXTVLCOPT:").trim()
                    when {
                        opt.startsWith("http-user-agent=", ignoreCase = true) ->
                            pendingHeaders["User-Agent"] = opt.substringAfter("=")
                        opt.startsWith("http-referrer=", ignoreCase = true) ->
                            pendingHeaders["Referer"] = opt.substringAfter("=")
                    }
                }
                line.startsWith("#") -> continue
                else -> {
                    val url = line
                    val extInf = pendingExtInf.orEmpty()
                    val attrs = mutableMapOf<String, String>()
                    attrRegex.findAll(extInf).forEach { match ->
                        attrs[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
                    }

                    val tvgId = attrs["tvg-id"].orEmpty()
                    val tvgName = attrs["tvg-name"].orEmpty()
                    val tvgLogo = attrs["tvg-logo"].orEmpty()
                    val groupTitle = attrs["group-title"].orEmpty().ifBlank { "General" }
                    val tvgChNo = attrs["tvg-chno"]?.toIntOrNull()
                    val displayName = extInf.substringAfterLast(',', "").trim()
                        .ifBlank { tvgName }
                        .ifBlank { "Channel $channelCounter" }

                    val contentType = CountryCategoryClassifier.inferContentType(groupTitle, displayName, url)
                    val catKey = "${contentType.name.lowercase()}_${groupTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"

                    categoryMap.getOrPut(catKey) {
                        ContentCategory(
                            id = catKey,
                            sourceId = sourceId,
                            name = groupTitle,
                            countryGroup = CountryCategoryClassifier.classifyCountryGroup(groupTitle),
                            type = contentType,
                            isSelected = false
                        )
                    }
                    categoryCounts[catKey] = (categoryCounts[catKey] ?: 0) + 1

                    val seasonEpisode = Regex("S(\\d{1,2})\\s*E(\\d{1,2})", RegexOption.IGNORE_CASE).find(displayName)
                    val seasonNum = seasonEpisode?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val episodeNum = seasonEpisode?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                    val seriesBaseName = if (contentType == ContentType.SERIES) {
                        displayName.replace(Regex("S\\d{1,2}\\s*E\\d{1,2}.*", RegexOption.IGNORE_CASE), "").trim()
                            .ifBlank { displayName }
                    } else {
                        ""
                    }

                    items += MediaItem(
                        id = "${sourceId}_m3u_${items.size}_${url.hashCode()}",
                        sourceId = sourceId,
                        title = displayName,
                        streamUrlOrCmd = url,
                        posterUrl = tvgLogo,
                        backdropUrl = tvgLogo,
                        categoryId = catKey,
                        type = contentType,
                        year = extractYearFromTitle(displayName),
                        epgChannelId = tvgId.ifBlank { tvgName },
                        seriesId = seriesBaseName,
                        seasonNumber = seasonNum,
                        episodeNumber = episodeNum,
                        channelNumber = if (contentType == ContentType.LIVE) (tvgChNo ?: channelCounter++) else 0,
                        addedAt = sequenceOrder++,
                        streamHeaders = pendingHeaders.toMap()
                    )

                    pendingExtInf = null
                    pendingHeaders.clear()
                }
            }
        }

        val finalizedCategories = categoryMap.values.map { cat ->
            cat.copy(itemCount = categoryCounts[cat.id] ?: 0)
        }
        return finalizedCategories to items
    }

    // -------------------------------------------------------------------------
    // GITHUB GIST JSON SOURCE
    // -------------------------------------------------------------------------

    private suspend fun fetchGistJsonCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val rawUrl = resolveGistRawUrl(source.m3uUrl)
        val body = downloadText(rawUrl).trim()

        if (body.startsWith("#EXTM3U", ignoreCase = true) || body.contains("#EXTINF:")) {
            val (cats, items) = parseM3uContent(source.id, body)
            parsedItemBuffer[source.id] = items
            return@withContext cats
        }

        val (categories, items) = parseGistJson(source.id, body)
        parsedItemBuffer[source.id] = items
        categories
    }

    private suspend fun filterBufferedOrReloadGist(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> {
        val cached = parsedItemBuffer[source.id] ?: run {
            fetchGistJsonCategories(source)
            parsedItemBuffer[source.id].orEmpty()
        }
        val selectedIds = selectedCategories.map { it.id }.toSet()
        val filtered = cached.filter { it.categoryId in selectedIds }
        val live = filtered.filter { it.type == ContentType.LIVE }
        val vodAndSeries = filtered.filter { it.type != ContentType.LIVE }
            .sortedByDescending { it.addedAt }
        return live + vodAndSeries
    }

    private fun resolveGistRawUrl(url: String): String {
        val clean = url.trim()
        return if (clean.contains("gist.github.com") && !clean.contains("/raw")) {
            clean.trimEnd('/') + "/raw"
        } else {
            clean
        }
    }

    private fun parseGistJson(sourceId: String, jsonText: String): Pair<List<ContentCategory>, List<MediaItem>> {
        val entries = mutableListOf<JSONObject>()
        if (jsonText.startsWith("[")) {
            val arr = JSONArray(jsonText)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { entries += it }
            }
        } else {
            val root = JSONObject(jsonText)
            listOf("channels", "items", "streams", "live", "movies", "vod", "series", "data").forEach { key ->
                val arr = root.optJSONArray(key)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { obj ->
                            if (!obj.has("type")) {
                                val inferred = when (key) {
                                    "movies", "vod" -> "VOD"
                                    "series" -> "SERIES"
                                    else -> "LIVE"
                                }
                                obj.put("type", inferred)
                            }
                            entries += obj
                        }
                    }
                }
            }
        }

        val categoryMap = linkedMapOf<String, ContentCategory>()
        val categoryCounts = mutableMapOf<String, Int>()
        val items = mutableListOf<MediaItem>()
        var chNumber = 1

        for ((index, obj) in entries.withIndex()) {
            val title = obj.optCleanString("title")
                .ifBlank { obj.optCleanString("name") }
                .ifBlank { obj.optCleanString("channel") }
            val url = obj.optCleanString("url")
                .ifBlank { obj.optCleanString("stream_url") }
                .ifBlank { obj.optCleanString("streamUrl") }
                .ifBlank { obj.optCleanString("link") }
            if (title.isBlank() || url.isBlank()) continue

            val group = obj.optCleanString("group")
                .ifBlank { obj.optCleanString("category") }
                .ifBlank { obj.optCleanString("group_title") }
                .ifBlank { "Gist Channels" }
            val explicitType = obj.optCleanString("type").uppercase()
            val contentType = when {
                explicitType.contains("VOD") || explicitType.contains("MOVIE") -> ContentType.VOD
                explicitType.contains("SERIES") -> ContentType.SERIES
                explicitType.contains("LIVE") -> ContentType.LIVE
                else -> CountryCategoryClassifier.inferContentType(group, title, url)
            }

            val catId = "${contentType.name.lowercase()}_${group.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
            categoryMap.getOrPut(catId) {
                ContentCategory(
                    id = catId,
                    sourceId = sourceId,
                    name = group,
                    countryGroup = CountryCategoryClassifier.classifyCountryGroup(group),
                    type = contentType,
                    isSelected = false
                )
            }
            categoryCounts[catId] = (categoryCounts[catId] ?: 0) + 1

            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("headers")?.let { hObj ->
                val hKeys = hObj.keys()
                while (hKeys.hasNext()) {
                    val hk = hKeys.next()
                    headers[hk] = hObj.optString(hk)
                }
            }

            val addedAt = parseXtreamAddedMs(
                obj.optCleanString("added").ifBlank { obj.optCleanString("addedAt") },
                fallbackId = (index + 1).toLong()
            )

            items += MediaItem(
                id = "${sourceId}_gist_${index}_${url.hashCode()}",
                sourceId = sourceId,
                title = title,
                streamUrlOrCmd = url,
                posterUrl = obj.optCleanString("poster")
                    .ifBlank { obj.optCleanString("posterUrl") }
                    .ifBlank { obj.optCleanString("cover") }
                    .ifBlank { obj.optCleanString("logo") },
                backdropUrl = obj.optCleanString("backdrop")
                    .ifBlank { obj.optCleanString("backdropUrl") },
                categoryId = catId,
                type = contentType,
                rating = normalizeRating(obj.optCleanString("rating")),
                plot = obj.optCleanString("plot").ifBlank { obj.optCleanString("description") },
                year = obj.optCleanString("year").ifBlank { extractYearFromTitle(title) },
                epgChannelId = obj.optCleanString("epg_id").ifBlank { obj.optCleanString("tvg_id") },
                seriesId = obj.optCleanString("series_id"),
                seasonNumber = obj.optInt("season", 0),
                episodeNumber = obj.optInt("episode", 0),
                channelNumber = if (contentType == ContentType.LIVE) chNumber++ else 0,
                addedAt = addedAt,
                streamHeaders = headers
            )
        }

        val finalizedCategories = categoryMap.values.map { cat ->
            cat.copy(itemCount = categoryCounts[cat.id] ?: 0)
        }
        return finalizedCategories to items
    }

    private fun parseXtreamAddedMs(raw: String, fallbackId: Long): Long {
        val cleaned = raw.trim()
        val numeric = cleaned.toLongOrNull()
        if (numeric != null && numeric > 0L) {
            return if (numeric > 10_000_000_000L) numeric else numeric * 1000L
        }
        return fallbackId
    }

    private fun normalizeRating(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val parsed = trimmed.toFloatOrNull() ?: return ""
        return if (parsed > 0f) trimmed else ""
    }

    private fun extractYearFromTitle(title: String): String {
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val raw = opt(key) ?: return ""
        if (raw is Boolean) return ""
        val str = raw.toString().trim()
        return if (str.equals("null", ignoreCase = true) || str.equals("false", ignoreCase = true)) {
            ""
        } else {
            str
        }
    }

    private fun downloadText(rawUrl: String): String {
        val url = if (rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            rawUrl.trim()
        } else {
            "https://${rawUrl.trim()}"
        }
        val client = RoutingManager.buildHttpClient(readTimeoutSec = 35L)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TiviWaveTV/1.0 (AndroidTV)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to download playlist (HTTP ${response.code})")
            }
            return response.body?.string().orEmpty()
        }
    }
}
