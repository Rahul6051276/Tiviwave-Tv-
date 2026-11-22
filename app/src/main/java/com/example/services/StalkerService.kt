package com.example.services

import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.EpgProgram
import com.example.models.MediaItem
import com.example.models.PlaylistSource
import com.example.models.ResolvedStream
import com.example.models.SeasonGroup
import com.example.routing.RoutingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class StalkerService {

    companion object {
        const val MAG250_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val MAG250_X_USER_AGENT = "Model: MAG250; Link: WiFi"
        private const val MAX_PAGES_PER_CATEGORY = 8
    }

    private data class StalkerSession(
        val endpointUrl: String,
        val token: String,
        val random: String = "",
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val sessionCache = ConcurrentHashMap<String, StalkerSession>()

    private fun buildCookieHeader(source: PlaylistSource): String {
        val encodedMac = URLEncoder.encode(source.macAddress.trim().uppercase(), "UTF-8")
        val parts = mutableListOf(
            "mac=$encodedMac",
            "stb_lang=en",
            "timezone=Asia/Kolkata"
        )
        if (source.serialNumber.isNotBlank()) {
            parts += "sn=${URLEncoder.encode(source.serialNumber.trim(), "UTF-8")}"
        }
        if (source.deviceId1.isNotBlank()) {
            parts += "device_id=${URLEncoder.encode(source.deviceId1.trim(), "UTF-8")}"
        }
        if (source.deviceId2.isNotBlank()) {
            parts += "device_id2=${URLEncoder.encode(source.deviceId2.trim(), "UTF-8")}"
        }
        return parts.joinToString("; ")
    }

    fun buildStalkerHeaders(source: PlaylistSource, token: String? = null): Map<String, String> {
        val baseReferer = normalizePortalBase(source.portalUrl).trimEnd('/') + "/c/"
        return buildMap {
            put("User-Agent", MAG250_USER_AGENT)
            put("X-User-Agent", MAG250_X_USER_AGENT)
            put("Cookie", buildCookieHeader(source))
            put("Referer", baseReferer)
            put("Accept", "*/*")
            put("Connection", "Keep-Alive")
            if (!token.isNullOrBlank()) {
                put("Authorization", "Bearer $token")
            }
        }
    }

    private fun normalizePortalBase(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            clean = "http://$clean"
        }
        return clean
            .removeSuffix("/c/")
            .removeSuffix("/c")
            .removeSuffix("/portal.php")
            .removeSuffix("/server/load.php")
            .trimEnd('/')
    }

    private fun extractOrigin(rawUrl: String): String {
        val base = normalizePortalBase(rawUrl)
        val parsed = base.toHttpUrlOrNull() ?: return base
        val defaultPort = (parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        return if (defaultPort) {
            "${parsed.scheme}://${parsed.host}"
        } else {
            "${parsed.scheme}://${parsed.host}:${parsed.port}"
        }
    }

    private fun candidateEndpoints(rawUrl: String): List<String> {
        val trimmed = rawUrl.trim()
        val base = normalizePortalBase(trimmed)
        val candidates = linkedSetOf<String>()
        if (trimmed.contains("portal.php", ignoreCase = true) ||
            trimmed.contains("load.php", ignoreCase = true)
        ) {
            val withScheme = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "http://$trimmed"
            candidates.add(withScheme)
        }
        candidates.add("$base/portal.php")
        candidates.add("$base/stalker_portal/server/load.php")
        candidates.add("$base/server/load.php")
        candidates.add("$base/c/portal.php")
        return candidates.toList()
    }

    private fun executeRequest(
        endpointUrl: String,
        params: Map<String, String>,
        source: PlaylistSource,
        token: String? = null
    ): String {
        val urlBuilder = endpointUrl.toHttpUrlOrNull()?.newBuilder()
            ?: error("Invalid Stalker portal URL: $endpointUrl")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        urlBuilder.addQueryParameter("JsHttpRequest", "1-xml")

        val headers = buildStalkerHeaders(source, token)
        val client = RoutingManager.buildHttpClient(customHeaders = headers)
        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Stalker portal HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    /**
     * Performs Stalker handshake (`type=stb&action=handshake`) followed by `get_profile`
     * with MAG MAC, Serial Number, Device ID1, Device ID2, and optional signature.
     */
    private suspend fun ensureSession(
        source: PlaylistSource,
        forceRefresh: Boolean = false
    ): StalkerSession = withContext(Dispatchers.IO) {
        val cached = sessionCache[source.id]
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.createdAtMs < 15 * 60_000L) {
            return@withContext cached
        }

        var lastError: Exception? = null
        for (endpoint in candidateEndpoints(source.portalUrl)) {
            try {
                val handshakeRaw = executeRequest(
                    endpointUrl = endpoint,
                    params = mapOf(
                        "type" to "stb",
                        "action" to "handshake",
                        "token" to "",
                        "prehash" to "0"
                    ),
                    source = source,
                    token = null
                )
                val handshakeJson = parseJsonSafely(handshakeRaw) ?: continue
                val jsObj = handshakeJson.optJSONObject("js") ?: continue
                val token = jsObj.optCleanString("token")
                val random = jsObj.optCleanString("random")
                if (token.isEmpty()) continue

                val profileParams = buildMap {
                    put("type", "stb")
                    put("action", "get_profile")
                    put("hd", "1")
                    put(
                        "ver",
                        "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
                    )
                    put("num_banks", "2")
                    put("stb_type", "MAG250")
                    put("image_version", "218")
                    put("auth_second_step", "1")
                    put("hw_version", "1.7-BD-00")
                    put("not_valid_token", "0")
                    put(
                        "metrics",
                        "{\"mac\":\"${source.macAddress.trim().uppercase()}\",\"sn\":\"${source.serialNumber.trim()}\",\"model\":\"MAG250\",\"type\":\"STB\"}"
                    )
                    if (source.serialNumber.isNotBlank()) put("sn", source.serialNumber.trim())
                    if (source.deviceId1.isNotBlank()) put("device_id", source.deviceId1.trim())
                    if (source.deviceId2.isNotBlank()) put("device_id2", source.deviceId2.trim())
                    if (source.signature.isNotBlank()) put("signature", source.signature.trim())
                }

                runCatching {
                    executeRequest(
                        endpointUrl = endpoint,
                        params = profileParams,
                        source = source,
                        token = token
                    )
                }

                val session = StalkerSession(endpointUrl = endpoint, token = token, random = random)
                sessionCache[source.id] = session
                return@withContext session
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Stalker handshake failed for ${source.portalUrl}")
    }

    /**
     * Step 1 Metadata-Only Fetch: Fetches Live TV genres, VOD categories, and Series categories.
     */
    suspend fun fetchAllCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val session = ensureSession(source, forceRefresh = true)
        val categories = mutableListOf<ContentCategory>()

        runCatching {
            val raw = executeRequest(
                endpointUrl = session.endpointUrl,
                params = mapOf("type" to "itv", "action" to "get_genres"),
                source = source,
                token = session.token
            )
            categories += parseCategoryArray(raw, source.id, ContentType.LIVE)
        }

        runCatching {
            val raw = executeRequest(
                endpointUrl = session.endpointUrl,
                params = mapOf("type" to "vod", "action" to "get_categories"),
                source = source,
                token = session.token
            )
            categories += parseCategoryArray(raw, source.id, ContentType.VOD)
        }

        runCatching {
            val raw = executeRequest(
                endpointUrl = session.endpointUrl,
                params = mapOf("type" to "series", "action" to "get_categories"),
                source = source,
                token = session.token
            )
            categories += parseCategoryArray(raw, source.id, ContentType.SERIES)
        }

        categories.distinctBy { "${it.type.name}:${it.id}" }
    }

    private fun parseCategoryArray(
        rawBody: String,
        sourceId: String,
        type: ContentType
    ): List<ContentCategory> {
        val json = parseJsonSafely(rawBody) ?: return emptyList()
        val array = json.optJSONArray("js") ?: return emptyList()
        val result = mutableListOf<ContentCategory>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optCleanString("id")
            val title = obj.optCleanString("title").ifBlank { obj.optCleanString("name") }
            if (id.isBlank() || id == "*" || title.isBlank() || title.equals("All", ignoreCase = true)) {
                continue
            }
            result += ContentCategory(
                id = "${type.name.lowercase()}_$id",
                sourceId = sourceId,
                name = title,
                countryGroup = CountryCategoryClassifier.classifyCountryGroup(title),
                type = type,
                isSelected = false
            )
        }
        return result
    }

    /**
     * Step 2 Selective Import: Strictly downloads items ONLY for checked categories.
     * Passes `sortby=added` for VOD and Web Series so newly uploaded server releases are
     * fetched and sorted at the very top (descending by `added` timestamp / numeric ID).
     */
    suspend fun fetchItemsForCategories(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        if (selectedCategories.isEmpty()) return@withContext emptyList()
        val session = ensureSession(source)
        val imported = mutableListOf<MediaItem>()

        for (category in selectedCategories) {
            val rawCatId = category.id.substringAfter('_', category.id)
            val stalkerType = when (category.type) {
                ContentType.LIVE -> "itv"
                ContentType.VOD -> "vod"
                ContentType.SERIES -> "series"
            }
            val filterParamKey = if (category.type == ContentType.LIVE) "genre" else "category"
            val sortParam = if (category.type == ContentType.LIVE) "number" else "added"

            var page = 1
            var totalExpected = Int.MAX_VALUE
            var fetchedForCategory = 0

            while (page <= MAX_PAGES_PER_CATEGORY && fetchedForCategory < totalExpected) {
                val raw = runCatching {
                    executeRequest(
                        endpointUrl = session.endpointUrl,
                        params = mapOf(
                            "type" to stalkerType,
                            "action" to "get_ordered_list",
                            filterParamKey to rawCatId,
                            "sortby" to sortParam,
                            "p" to page.toString()
                        ),
                        source = source,
                        token = session.token
                    )
                }.getOrNull() ?: break

                val json = parseJsonSafely(raw) ?: break
                val jsObj = json.optJSONObject("js") ?: break
                val totalItems = jsObj.optInt("total_items", 0)
                if (totalItems > 0) totalExpected = totalItems
                val dataArray = jsObj.optJSONArray("data") ?: break
                if (dataArray.length() == 0) break

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.optJSONObject(i) ?: continue
                    val item = parseMediaItem(obj, source, session, category)
                    if (item != null) {
                        imported += item
                    }
                }

                fetchedForCategory += dataArray.length()
                val maxPageItems = jsObj.optInt("max_page_items", 14).coerceAtLeast(1)
                if (dataArray.length() < maxPageItems) break
                page++
            }
        }

        val deduped = imported.distinctBy { "${it.type.name}:${it.id}" }
        val liveItems = deduped.filter { it.type == ContentType.LIVE }
            .sortedWith(compareBy<MediaItem> { if (it.channelNumber > 0) it.channelNumber else Int.MAX_VALUE }.thenBy { it.title })
        val vodAndSeries = deduped.filter { it.type != ContentType.LIVE }
            .sortedWith(
                compareByDescending<MediaItem> { it.addedAt }
                    .thenByDescending { extractNumericId(it.id) }
            )
        liveItems + vodAndSeries
    }

    private fun parseMediaItem(
        obj: JSONObject,
        source: PlaylistSource,
        session: StalkerSession,
        category: ContentCategory
    ): MediaItem? {
        val rawId = obj.optCleanString("id")
        val name = obj.optCleanString("name").ifBlank { obj.optCleanString("o_name") }
        if (rawId.isBlank() || name.isBlank()) return null

        val cmd = obj.optCleanString("cmd").ifBlank {
            obj.optJSONArray("cmds")?.optJSONObject(0)?.optCleanString("url").orEmpty()
        }

        // For VOD & Series prioritize unique item artwork (`screenshot_uri` / `cover` / `poster`)
        // before falling back to `logo` so series cards never show a shared/generic logo.
        val rawPoster = when (category.type) {
            ContentType.LIVE -> firstValidImageField(obj, listOf("logo", "screenshot_uri", "pic", "cover"))
            ContentType.VOD,
            ContentType.SERIES -> firstValidImageField(
                obj,
                listOf("screenshot_uri", "cover", "poster", "poster_url", "pic", "logo")
            )
        }
        val rawBackdrop = firstValidImageField(
            obj,
            listOf("backdrop_path", "backdrop", "screenshot_uri", "cover", "poster", "pic")
        ).ifBlank { rawPoster }

        val resolvedPoster = resolvePortalAssetUrl(source.portalUrl, rawPoster)
        val resolvedBackdrop = resolvePortalAssetUrl(source.portalUrl, rawBackdrop)

        val number = obj.optCleanString("number").toIntOrNull() ?: 0
        val rating = normalizeRating(
            obj.optCleanString("rating_imdb")
                .ifBlank { obj.optCleanString("rating_kinopoisk") }
                .ifBlank { obj.optCleanString("rating") }
        )
        val year = obj.optCleanString("year")
            .ifBlank { extractYearFromTitle(name) }
        val plot = obj.optCleanString("description")

        // Parse Stalker `added` date/timestamp, falling back to numeric item ID for deterministic newest-first order
        val addedRaw = obj.optCleanString("added")
            .ifBlank { obj.optCleanString("added_timestamp") }
            .ifBlank { obj.optCleanString("last_modified") }
        val numericId = rawId.filter(Char::isDigit).toLongOrNull() ?: 0L
        val addedAt = parseStalkerTimestampMs(addedRaw).takeIf { it > 0L } ?: numericId

        return MediaItem(
            id = "${source.id}_${category.type.name.lowercase()}_$rawId",
            sourceId = source.id,
            title = name,
            streamUrlOrCmd = cmd,
            posterUrl = resolvedPoster,
            backdropUrl = resolvedBackdrop,
            categoryId = category.id,
            type = category.type,
            rating = rating,
            plot = plot,
            year = year,
            epgChannelId = rawId,
            seriesId = if (category.type == ContentType.SERIES) rawId else "",
            channelNumber = number,
            addedAt = addedAt,
            streamHeaders = buildStalkerHeaders(source, session.token)
        )
    }

    /**
     * Fetches Seasons & Episodes for a Stalker Web Series on demand.
     */
    suspend fun fetchSeriesSeasons(
        source: PlaylistSource,
        seriesItem: MediaItem
    ): List<SeasonGroup> = withContext(Dispatchers.IO) {
        val session = ensureSession(source)
        val rawSeriesId = seriesItem.seriesId.ifBlank {
            seriesItem.id.substringAfterLast('_')
        }

        val seasonsRaw = executeRequest(
            endpointUrl = session.endpointUrl,
            params = mapOf(
                "type" to "series",
                "action" to "get_ordered_list",
                "movie_id" to rawSeriesId,
                "p" to "1"
            ),
            source = source,
            token = session.token
        )
        val seasonsJson = parseJsonSafely(seasonsRaw)?.optJSONObject("js") ?: return@withContext emptyList()
        val seasonsData = seasonsJson.optJSONArray("data") ?: return@withContext emptyList()
        val groups = mutableListOf<SeasonGroup>()

        for (i in 0 until seasonsData.length()) {
            val seasonObj = seasonsData.optJSONObject(i) ?: continue
            val seasonId = seasonObj.optCleanString("id")
            val seasonName = seasonObj.optCleanString("name").ifBlank { "Season ${i + 1}" }
            val seasonNum = Regex("\\d+").find(seasonName)?.value?.toIntOrNull() ?: (i + 1)

            val episodesRaw = runCatching {
                executeRequest(
                    endpointUrl = session.endpointUrl,
                    params = mapOf(
                        "type" to "series",
                        "action" to "get_ordered_list",
                        "movie_id" to rawSeriesId,
                        "season_id" to seasonId,
                        "p" to "1"
                    ),
                    source = source,
                    token = session.token
                )
            }.getOrNull()

            val episodesList = mutableListOf<MediaItem>()
            val episodesArray = episodesRaw?.let { parseJsonSafely(it)?.optJSONObject("js")?.optJSONArray("data") }
            val seriesCmd = seasonObj.optCleanString("cmd").ifBlank { seriesItem.streamUrlOrCmd }

            if (episodesArray != null && episodesArray.length() > 0) {
                for (e in 0 until episodesArray.length()) {
                    val epObj = episodesArray.optJSONObject(e) ?: continue
                    val epId = epObj.optCleanString("id").ifBlank { "${seasonNum}_${e + 1}" }
                    val epNum = epObj.optInt("series_number", e + 1)
                    val epTitle = epObj.optCleanString("name").ifBlank { "Episode $epNum" }
                    val epCmd = epObj.optCleanString("cmd").ifBlank { seriesCmd }
                    episodesList += MediaItem(
                        id = "${source.id}_ep_${rawSeriesId}_${seasonNum}_$epId",
                        sourceId = source.id,
                        title = epTitle,
                        streamUrlOrCmd = epCmd,
                        posterUrl = seriesItem.posterUrl,
                        backdropUrl = seriesItem.backdropUrl,
                        categoryId = seriesItem.categoryId,
                        type = ContentType.SERIES,
                        rating = seriesItem.rating,
                        plot = epObj.optCleanString("description").ifBlank { seriesItem.plot },
                        year = seriesItem.year,
                        seriesId = rawSeriesId,
                        seasonNumber = seasonNum,
                        episodeNumber = epNum,
                        addedAt = seriesItem.addedAt,
                        streamHeaders = buildStalkerHeaders(source, session.token)
                    )
                }
            } else {
                val episodeNumbers = seasonObj.optJSONArray("series")
                if (episodeNumbers != null) {
                    for (e in 0 until episodeNumbers.length()) {
                        val epNum = episodeNumbers.optInt(e, e + 1)
                        episodesList += MediaItem(
                            id = "${source.id}_ep_${rawSeriesId}_${seasonNum}_$epNum",
                            sourceId = source.id,
                            title = "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} • ${seriesItem.title}",
                            streamUrlOrCmd = "$seriesCmd||episode=$epNum",
                            posterUrl = seriesItem.posterUrl,
                            backdropUrl = seriesItem.backdropUrl,
                            categoryId = seriesItem.categoryId,
                            type = ContentType.SERIES,
                            rating = seriesItem.rating,
                            plot = seriesItem.plot,
                            year = seriesItem.year,
                            seriesId = rawSeriesId,
                            seasonNumber = seasonNum,
                            episodeNumber = epNum,
                            addedAt = seriesItem.addedAt,
                            streamHeaders = buildStalkerHeaders(source, session.token)
                        )
                    }
                }
            }

            if (episodesList.isNotEmpty()) {
                groups += SeasonGroup(
                    seasonNumber = seasonNum,
                    title = seasonName,
                    episodes = episodesList.sortedBy { it.episodeNumber }
                )
            }
        }

        groups.sortedBy { it.seasonNumber }
    }

    /**
     * Dynamically resolves a playable stream URL via Stalker `create_link`,
     * stripping `ffmpeg `, `ffrt `, `ffrt2 `, `ffrt3 `, and `auto ` prefixes.
     */
    suspend fun resolveStreamLink(
        source: PlaylistSource,
        item: MediaItem,
        forceSessionRefresh: Boolean = false
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val rawCmd = item.streamUrlOrCmd.trim()
        val directCleaned = stripStreamCommandPrefix(rawCmd)
        val session = ensureSession(source, forceRefresh = forceSessionRefresh)

        val episodeSuffix = if (rawCmd.contains("||episode=")) {
            rawCmd.substringAfter("||episode=")
        } else if (item.episodeNumber > 0) {
            item.episodeNumber.toString()
        } else {
            ""
        }
        val cleanCmdForApi = rawCmd.substringBefore("||episode=")

        val stalkerType = when (item.type) {
            ContentType.LIVE -> "itv"
            ContentType.VOD, ContentType.SERIES -> "vod"
        }

        val params = buildMap {
            put("type", stalkerType)
            put("action", "create_link")
            put("cmd", cleanCmdForApi)
            put("forced_storage", "undefined")
            put("disable_ad", "0")
            put("download", "0")
            if (item.type == ContentType.SERIES && episodeSuffix.isNotBlank()) {
                put("series", episodeSuffix)
            }
        }

        val responseRaw = runCatching {
            executeRequest(
                endpointUrl = session.endpointUrl,
                params = params,
                source = source,
                token = session.token
            )
        }.getOrNull()

        val resolvedCmd = responseRaw
            ?.let { parseJsonSafely(it)?.optJSONObject("js")?.optCleanString("cmd") }
            .orEmpty()

        val finalUrl = stripStreamCommandPrefix(resolvedCmd.ifBlank { directCleaned })
        if (finalUrl.isBlank()) {
            error("Unable to resolve playable URL from Stalker create_link")
        }

        ResolvedStream(
            url = finalUrl,
            headers = buildStalkerHeaders(source, session.token)
        )
    }

    /**
     * Fetches short EPG entries directly from a Stalker Portal for a live channel.
     */
    suspend fun fetchShortEpg(
        source: PlaylistSource,
        channelEpgId: String
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        if (channelEpgId.isBlank()) return@withContext emptyList()
        runCatching {
            val session = ensureSession(source)
            val raw = executeRequest(
                endpointUrl = session.endpointUrl,
                params = mapOf(
                    "type" to "itv",
                    "action" to "get_short_epg",
                    "ch_id" to channelEpgId,
                    "size" to "10"
                ),
                source = source,
                token = session.token
            )
            val arr = parseJsonSafely(raw)?.optJSONArray("js") ?: return@runCatching emptyList()
            val programs = mutableListOf<EpgProgram>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val startSec = obj.optLong("start_timestamp", 0L)
                val stopSec = obj.optLong("stop_timestamp", 0L)
                val title = obj.optCleanString("name")
                if (title.isBlank() || startSec <= 0L) continue
                programs += EpgProgram(
                    channelId = channelEpgId,
                    title = title,
                    description = obj.optCleanString("descr"),
                    startTimeMs = startSec * 1000L,
                    endTimeMs = (if (stopSec > startSec) stopSec else startSec + 1800L) * 1000L
                )
            }
            programs.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    fun stripStreamCommandPrefix(cmd: String): String {
        val cleaned = cmd.trim()
        val prefixes = listOf("ffmpeg ", "ffrt4 ", "ffrt3 ", "ffrt2 ", "ffrt ", "auto ", "solution ")
        var result = cleaned
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (result.startsWith(prefix, ignoreCase = true)) {
                    result = result.substring(prefix.length).trim()
                    changed = true
                }
            }
        }
        val urlMatch = Regex("(https?|rtsp|rtmp)://\\S+", RegexOption.IGNORE_CASE).find(result)
        return urlMatch?.value ?: result
    }

    /**
     * Resolves Stalker relative asset URLs cleanly against the portal origin or base URL.
     * Prevents duplicated path segments like `/stalker_portal/stalker_portal/...` that cause
     * portals to serve a single fallback placeholder poster for every series card.
     */
    fun resolvePortalAssetUrl(portalUrl: String, assetPath: String): String {
        val clean = assetPath.trim()
        if (clean.isBlank() || clean.equals("null", ignoreCase = true) || clean.equals("false", ignoreCase = true)) {
            return ""
        }
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        if (clean.startsWith("//")) {
            val scheme = if (portalUrl.startsWith("https://", ignoreCase = true)) "https:" else "http:"
            return "$scheme$clean"
        }
        if (clean.startsWith("/")) {
            val origin = extractOrigin(portalUrl)
            return "$origin$clean"
        }
        val base = normalizePortalBase(portalUrl)
        return "$base/$clean"
    }

    private fun firstValidImageField(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val raw = obj.opt(key)
            val candidate = when (raw) {
                is org.json.JSONArray -> raw.optString(0).orEmpty()
                else -> obj.optCleanString(key)
            }.trim()
            if (candidate.isNotBlank() &&
                !candidate.equals("null", ignoreCase = true) &&
                !candidate.equals("false", ignoreCase = true) &&
                !candidate.equals("0", ignoreCase = true) &&
                !candidate.equals("none", ignoreCase = true)
            ) {
                return candidate
            }
        }
        return ""
    }

    private fun normalizeRating(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val parsed = trimmed.toFloatOrNull() ?: return ""
        return if (parsed > 0f) trimmed else ""
    }

    private fun parseStalkerTimestampMs(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L
        trimmed.toLongOrNull()?.let { numeric ->
            return if (numeric > 10_000_000_000L) numeric else numeric * 1000L
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            }
        }
        return 0L
    }

    private fun extractYearFromTitle(title: String): String {
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractNumericId(compositeId: String): Long {
        return compositeId.substringAfterLast('_').filter(Char::isDigit).toLongOrNull() ?: 0L
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

    private fun parseJsonSafely(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val startIdx = trimmed.indexOf('{')
        if (startIdx < 0) return null
        return runCatching { JSONObject(trimmed.substring(startIdx)) }.getOrNull()
    }
}
