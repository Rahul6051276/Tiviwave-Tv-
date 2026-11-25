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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class XtreamAndM3uService(private val context: Context) {

    // Temporary in-memory parse cache for M3U / Gist sources between Step 1 (Categories) and Step 2 (Import)
    private val parsedItemBuffer = ConcurrentHashMap<String, List<MediaItem>>()

    // Temporary in-memory cache for Xtream fallback streams (keyed by "sourceId:CONTENT_TYPE")
    // populated when `get_*_categories` returns empty and categories are derived directly from streams.
    private val xtreamFallbackBuffer = ConcurrentHashMap<String, List<MediaItem>>()

    private data class XtreamAccountInfo(
        val status: String,
        val expDateFormatted: String
    )

    private data class XtreamCategorySpec(
        val categoryAction: String,
        val streamAction: String,
        val contentType: ContentType,
        val defaultCategoryId: String,
        val defaultCategoryName: String
    )

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

    /**
     * Cleanly normalizes Xtream host/URL by ensuring scheme, removing query strings/fragments,
     * and stripping trailing slashes as well as `/player_api.php`, `/get.php`, `/index.php`,
     * `/panel_api.php`, or `/xmltv.php`.
     */
    fun normalizeBaseUrl(url: String): String {
        var clean = url.trim()
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            clean = "http://$clean"
        }
        clean = clean.substringBefore('#').substringBefore('?').trim()

        val suffixes = listOf(
            "/player_api.php",
            "/get.php",
            "/index.php",
            "/panel_api.php",
            "/xmltv.php"
        )
        var changed = true
        while (changed) {
            changed = false
            clean = clean.trimEnd('/')
            for (suffix in suffixes) {
                if (clean.endsWith(suffix, ignoreCase = true)) {
                    clean = clean.dropLast(suffix.length).trimEnd('/')
                    changed = true
                }
            }
        }
        return clean.trimEnd('/')
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
        return "$base/xmltv.php?username=${source.username.trim()}&password=${source.password.trim()}"
    }

    private fun executeXtreamApi(
        source: PlaylistSource,
        action: String? = null,
        extra: Map<String, String> = emptyMap()
    ): String {
        val base = normalizeBaseUrl(source.portalUrl)
        if (base.isBlank()) {
            error("Xtream Host URL is required.")
        }
        val user = source.username.trim()
        val pass = source.password.trim()
        if (user.isBlank() || pass.isBlank()) {
            error("Xtream username and password are required.")
        }

        val urlBuilder = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: error("Invalid Xtream portal URL: $base")
        urlBuilder.addQueryParameter("username", user)
        urlBuilder.addQueryParameter("password", pass)
        if (!action.isNullOrBlank()) {
            urlBuilder.addQueryParameter("action", action)
        }
        extra.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val client = RoutingManager.buildHttpClient(
            connectTimeoutSec = 8L,
            readTimeoutSec = 12L
        )
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "TiviWaveTV/1.0 (AndroidTV)")
            .header("Accept", "application/json, */*")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401, 403 -> "invalid credentials or access blocked"
                    404 -> "player_api.php not found at $base"
                    in 500..599 -> "Xtream server error"
                    else -> "HTTP error"
                }
                error("Xtream API HTTP ${response.code} ($reason)")
            }
            return body
        }
    }

    /**
     * Calls base `player_api.php` (without `action`) and inspects `user_info` (`auth`, `status`, `exp_date`)
     * to provide accurate, actionable error messages when credentials, account status, or URL fail.
     */
    private fun validateXtreamAccount(source: PlaylistSource): XtreamAccountInfo {
        val base = normalizeBaseUrl(source.portalUrl)
        val raw = executeXtreamApi(source, action = null).trim()
        if (raw.isEmpty()) {
            error("Xtream server at $base returned an empty response. Check Host URL or proxy settings.")
        }
        if (raw.startsWith("<")) {
            error("Server at $base returned an HTML page instead of Xtream API JSON. Verify the Host URL.")
        }

        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: error("Server at $base returned an invalid Xtream JSON response. Verify the Host URL.")

        val userInfo = root.optJSONObject("user_info")
            ?: return XtreamAccountInfo(status = "Active", expDateFormatted = "")

        val authRaw = userInfo.opt("auth")
        val isAuthFailed = when (authRaw) {
            is Number -> authRaw.toInt() == 0
            is Boolean -> !authRaw
            is String -> authRaw.trim() == "0" || authRaw.trim().equals("false", ignoreCase = true)
            else -> false
        }
        if (isAuthFailed) {
            val serverMsg = userInfo.optCleanString("message")
            val detail = if (serverMsg.isNotBlank()) " ($serverMsg)" else ""
            error("Xtream login failed: invalid username or password$detail.")
        }

        val expRaw = userInfo.optCleanString("exp_date")
        val expSec = expRaw.toLongOrNull() ?: 0L
        val formattedExp = if (expSec > 0L) formatUnixDate(expSec) else ""

        val status = userInfo.optCleanString("status")
        if (status.isNotBlank() &&
            !status.equals("Active", ignoreCase = true) &&
            !status.equals("Trial", ignoreCase = true)
        ) {
            val expSuffix = if (formattedExp.isNotBlank()) " (exp: $formattedExp)" else ""
            error("Xtream account status is '$status'$expSuffix. Check subscription with your provider.")
        }

        if (expSec > 0L && expSec * 1000L < System.currentTimeMillis()) {
            error("Xtream account expired on $formattedExp. Renew your subscription or update credentials.")
        }

        return XtreamAccountInfo(
            status = status.ifBlank { "Active" },
            expDateFormatted = formattedExp
        )
    }

    private fun clearXtreamFallbackBuffer(sourceId: String) {
        ContentType.entries.forEach { type ->
            xtreamFallbackBuffer.remove("${sourceId}:${type.name}")
        }
    }

    private suspend fun fetchXtreamCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val accountInfo = validateXtreamAccount(source)
        clearXtreamFallbackBuffer(source.id)

        val base = normalizeBaseUrl(source.portalUrl)
        val user = source.username.trim()
        val pass = source.password.trim()
        val categories = mutableListOf<ContentCategory>()
        var lastApiError: String? = null

        val specs = listOf(
            XtreamCategorySpec(
                categoryAction = "get_live_categories",
                streamAction = "get_live_streams",
                contentType = ContentType.LIVE,
                defaultCategoryId = "live_all",
                defaultCategoryName = "All Live Channels"
            ),
            XtreamCategorySpec(
                categoryAction = "get_vod_categories",
                streamAction = "get_vod_streams",
                contentType = ContentType.VOD,
                defaultCategoryId = "vod_all",
                defaultCategoryName = "All Movies"
            ),
            XtreamCategorySpec(
                categoryAction = "get_series_categories",
                streamAction = "get_series",
                contentType = ContentType.SERIES,
                defaultCategoryId = "series_all",
                defaultCategoryName = "All Series"
            )
        )

        for (spec in specs) {
            var typeCategories = emptyList<ContentCategory>()

            val catRaw = runCatching {
                executeXtreamApi(source, spec.categoryAction)
            }.onFailure { err ->
                if (lastApiError.isNullOrBlank()) lastApiError = err.message
            }.getOrNull()

            if (!catRaw.isNullOrBlank()) {
                typeCategories = parseXtreamCategoriesJson(catRaw, source.id, spec.contentType)
            }

            // Fallback: If get_*_categories returned empty [], fetch corresponding streams
            // and derive categories automatically from unique category_id + category_name
            // (or a default category if missing).
            if (typeCategories.isEmpty()) {
                val streamsRaw = runCatching {
                    executeXtreamApi(source, spec.streamAction)
                }.onFailure { err ->
                    if (lastApiError.isNullOrBlank()) lastApiError = err.message
                }.getOrNull()

                if (!streamsRaw.isNullOrBlank()) {
                    typeCategories = buildXtreamCategoriesFromStreams(
                        rawJson = streamsRaw,
                        source = source,
                        base = base,
                        user = user,
                        pass = pass,
                        spec = spec
                    )
                }
            }

            categories += typeCategories
        }

        val deduped = categories.distinctBy { "${it.type.name}:${it.id}" }
        if (deduped.isEmpty()) {
            val detail = lastApiError ?: buildString {
                append("Xtream account authenticated (Status: ${accountInfo.status}")
                if (accountInfo.expDateFormatted.isNotBlank()) {
                    append(", Exp: ${accountInfo.expDateFormatted}")
                }
                append("), but the server returned 0 Live, VOD, and Series categories or streams.")
            }
            error(detail)
        }
        deduped
    }

    private fun parseXtreamCategoriesJson(
        rawBody: String,
        sourceId: String,
        contentType: ContentType
    ): List<ContentCategory> {
        val objects = extractJsonObjectList(rawBody)
        if (objects.isEmpty()) return emptyList()

        val result = mutableListOf<ContentCategory>()
        val seenIds = mutableSetOf<String>()
        for (obj in objects) {
            val catId = obj.optCleanString("category_id").ifBlank { obj.optCleanString("id") }
            val catName = obj.optCleanString("category_name")
                .ifBlank { obj.optCleanString("name") }
                .ifBlank { obj.optCleanString("title") }
            if (catId.isBlank() || catName.isBlank()) continue

            val compositeId = "${contentType.name.lowercase()}_$catId"
            if (seenIds.add(compositeId)) {
                result += ContentCategory(
                    id = compositeId,
                    sourceId = sourceId,
                    name = catName,
                    countryGroup = CountryCategoryClassifier.classifyCountryGroup(catName),
                    type = contentType,
                    isSelected = false
                )
            }
        }
        return result
    }

    private fun buildXtreamCategoriesFromStreams(
        rawJson: String,
        source: PlaylistSource,
        base: String,
        user: String,
        pass: String,
        spec: XtreamCategorySpec
    ): List<ContentCategory> {
        val objects = extractJsonObjectList(rawJson)
        if (objects.isEmpty()) return emptyList()

        val categoryMap = linkedMapOf<String, ContentCategory>()
        val countMap = mutableMapOf<String, Int>()
        val parsedItems = mutableListOf<MediaItem>()

        for ((index, obj) in objects.withIndex()) {
            val rawCatId = extractXtreamStreamCategoryId(obj)
            val rawCatName = obj.optCleanString("category_name")
                .ifBlank { obj.optCleanString("category_title") }
                .ifBlank { obj.optCleanString("group_title") }

            val hasRealId = rawCatId.isNotBlank() && rawCatId != "0"
            val hasRealName = rawCatName.isNotBlank() && !rawCatName.equals("All", ignoreCase = true)

            val (catId, catName) = when {
                hasRealId -> {
                    val labelPrefix = when (spec.contentType) {
                        ContentType.LIVE -> "Live Group"
                        ContentType.VOD -> "VOD Group"
                        ContentType.SERIES -> "Series Group"
                    }
                    "${spec.contentType.name.lowercase()}_$rawCatId" to
                        (if (hasRealName) rawCatName else "$labelPrefix $rawCatId")
                }
                hasRealName -> {
                    val slug = rawCatName.lowercase(Locale.US)
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                        .ifBlank { "all" }
                    "${spec.contentType.name.lowercase()}_$slug" to rawCatName
                }
                else -> spec.defaultCategoryId to spec.defaultCategoryName
            }

            categoryMap.getOrPut(catId) {
                ContentCategory(
                    id = catId,
                    sourceId = source.id,
                    name = catName,
                    countryGroup = CountryCategoryClassifier.classifyCountryGroup(catName),
                    type = spec.contentType,
                    isSelected = false
                )
            }

            val item = parseXtreamStreamItem(
                obj = obj,
                index = index,
                source = source,
                base = base,
                user = user,
                pass = pass,
                contentType = spec.contentType,
                categoryId = catId
            )
            if (item != null) {
                parsedItems += item
                countMap[catId] = (countMap[catId] ?: 0) + 1
            }
        }

        if (parsedItems.isEmpty()) return emptyList()

        xtreamFallbackBuffer["${source.id}:${spec.contentType.name}"] = parsedItems.distinctBy { it.id }

        return categoryMap.values.map { cat ->
            cat.copy(itemCount = countMap[cat.id] ?: 0)
        }
    }

    private suspend fun fetchXtreamItemsForCategories(
        source: PlaylistSource,
        selectedCategories: List<ContentCategory>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(source.portalUrl)
        val user = source.username.trim()
        val pass = source.password.trim()
        val items = mutableListOf<MediaItem>()
        val unfilteredTypeCache = mutableMapOf<ContentType, List<JSONObject>>()

        for (category in selectedCategories) {
            val rawCatId = category.id.substringAfter('_', category.id)
            val isWildcard = rawCatId.equals("all", ignoreCase = true) || rawCatId == "*"

            // Use Step-1 stream-fallback buffer immediately when available
            val buffered = xtreamFallbackBuffer["${source.id}:${category.type.name}"].orEmpty()
            if (buffered.isNotEmpty()) {
                val matched = if (isWildcard) buffered else buffered.filter { it.categoryId == category.id }
                if (matched.isNotEmpty()) {
                    items += matched
                    continue
                }
            }

            val action = when (category.type) {
                ContentType.LIVE -> "get_live_streams"
                ContentType.VOD -> "get_vod_streams"
                ContentType.SERIES -> "get_series"
            }

            val parsedForCategory = mutableListOf<MediaItem>()
            runCatching {
                val extra = if (isWildcard) emptyMap() else mapOf("category_id" to rawCatId)
                val raw = executeXtreamApi(source, action, extra)
                val objects = extractJsonObjectList(raw)
                for ((i, obj) in objects.withIndex()) {
                    parseXtreamStreamItem(
                        obj = obj,
                        index = i,
                        source = source,
                        base = base,
                        user = user,
                        pass = pass,
                        contentType = category.type,
                        categoryId = category.id
                    )?.let { parsedForCategory += it }
                }
            }

            // If category_id-filtered endpoint returned empty (common on panels that broke category filtering),
            // fetch unfiltered streams once for this type and filter locally.
            if (parsedForCategory.isEmpty() && !isWildcard) {
                val allObjects = unfilteredTypeCache.getOrPut(category.type) {
                    runCatching {
                        extractJsonObjectList(executeXtreamApi(source, action))
                    }.getOrDefault(emptyList())
                }
                for ((i, obj) in allObjects.withIndex()) {
                    val objCatIds = extractAllXtreamStreamCategoryIds(obj)
                    val objCatName = obj.optCleanString("category_name")
                        .ifBlank { obj.optCleanString("category_title") }
                        .ifBlank { obj.optCleanString("group_title") }
                    val matchesCategory = rawCatId in objCatIds ||
                        (objCatName.isNotBlank() && objCatName.equals(category.name, ignoreCase = true))
                    if (matchesCategory) {
                        parseXtreamStreamItem(
                            obj = obj,
                            index = i,
                            source = source,
                            base = base,
                            user = user,
                            pass = pass,
                            contentType = category.type,
                            categoryId = category.id
                        )?.let { parsedForCategory += it }
                    }
                }
            }

            items += parsedForCategory
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

    private fun parseXtreamStreamItem(
        obj: JSONObject,
        index: Int,
        source: PlaylistSource,
        base: String,
        user: String,
        pass: String,
        contentType: ContentType,
        categoryId: String
    ): MediaItem? {
        return when (contentType) {
            ContentType.LIVE -> {
                val streamId = obj.optCleanString("stream_id").ifBlank { obj.optCleanString("id") }
                val name = obj.optCleanString("name").ifBlank { obj.optCleanString("title") }
                if (streamId.isBlank() || name.isBlank()) return null
                val iconUrl = resolveXtreamAssetUrl(base, obj.optCleanString("stream_icon"))
                MediaItem(
                    id = "${source.id}_live_$streamId",
                    sourceId = source.id,
                    title = name,
                    streamUrlOrCmd = "$base/live/$user/$pass/$streamId.ts",
                    posterUrl = iconUrl,
                    categoryId = categoryId,
                    type = ContentType.LIVE,
                    epgChannelId = obj.optCleanString("epg_channel_id").ifBlank { streamId },
                    channelNumber = obj.optInt("num", index + 1)
                )
            }

            ContentType.VOD -> {
                val streamId = obj.optCleanString("stream_id").ifBlank { obj.optCleanString("id") }
                val name = obj.optCleanString("name").ifBlank { obj.optCleanString("title") }
                val ext = obj.optCleanString("container_extension").ifBlank { "mp4" }
                if (streamId.isBlank() || name.isBlank()) return null
                val poster = resolveXtreamAssetUrl(
                    base,
                    obj.optCleanString("stream_icon").ifBlank { obj.optCleanString("cover") }
                )
                val addedAt = parseXtreamAddedMs(
                    obj.optCleanString("added"),
                    fallbackId = streamId.toLongOrNull() ?: index.toLong()
                )
                val rating = normalizeRating(
                    obj.optCleanString("rating").ifBlank { obj.optCleanString("rating_5based") }
                )
                MediaItem(
                    id = "${source.id}_vod_$streamId",
                    sourceId = source.id,
                    title = name,
                    streamUrlOrCmd = "$base/movie/$user/$pass/$streamId.$ext",
                    posterUrl = poster,
                    backdropUrl = poster,
                    categoryId = categoryId,
                    type = ContentType.VOD,
                    rating = rating,
                    year = obj.optCleanString("year").ifBlank { extractYearFromTitle(name) },
                    plot = obj.optCleanString("plot"),
                    addedAt = addedAt
                )
            }

            ContentType.SERIES -> {
                val seriesId = obj.optCleanString("series_id").ifBlank { obj.optCleanString("id") }
                val name = obj.optCleanString("name").ifBlank { obj.optCleanString("title") }
                if (seriesId.isBlank() || name.isBlank()) return null
                val rawBackdrop = obj.optJSONArray("backdrop_path")?.optString(0).orEmpty()
                val rawCover = obj.optCleanString("cover")
                    .ifBlank { obj.optCleanString("stream_icon") }
                    .ifBlank { obj.optCleanString("poster") }
                    .ifBlank { rawBackdrop }
                val poster = resolveXtreamAssetUrl(base, rawCover)
                val backdrop = resolveXtreamAssetUrl(base, rawBackdrop.ifBlank { rawCover })
                val addedAt = parseXtreamAddedMs(
                    obj.optCleanString("last_modified").ifBlank { obj.optCleanString("added") },
                    fallbackId = seriesId.toLongOrNull() ?: index.toLong()
                )
                val rating = normalizeRating(
                    obj.optCleanString("rating").ifBlank { obj.optCleanString("rating_5based") }
                )
                MediaItem(
                    id = "${source.id}_series_$seriesId",
                    sourceId = source.id,
                    title = name,
                    streamUrlOrCmd = "",
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    categoryId = categoryId,
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

    private fun extractXtreamStreamCategoryId(obj: JSONObject): String {
        val direct = obj.optCleanString("category_id")
        if (direct.isNotBlank()) return direct
        val catIds = obj.optJSONArray("category_ids")
        if (catIds != null && catIds.length() > 0) {
            val first = catIds.opt(0)?.toString()?.trim().orEmpty()
            if (first.isNotBlank() && !first.equals("null", ignoreCase = true)) {
                return first
            }
        }
        return ""
    }

    private fun extractAllXtreamStreamCategoryIds(obj: JSONObject): Set<String> {
        val ids = mutableSetOf<String>()
        obj.optCleanString("category_id").takeIf { it.isNotBlank() }?.let { ids += it }
        val catIds = obj.optJSONArray("category_ids")
        if (catIds != null) {
            for (i in 0 until catIds.length()) {
                val v = catIds.opt(i)?.toString()?.trim().orEmpty()
                if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) {
                    ids += v
                }
            }
        }
        return ids
    }

    private fun extractJsonObjectList(rawBody: String): List<JSONObject> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()
        val result = mutableListOf<JSONObject>()
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result += it }
            }
            return result
        }
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            // Ignore authentication error payloads (`{"user_info": ...}`) when expecting lists
            if (obj.has("user_info") && obj.length() <= 2) return emptyList()
            val keys = obj.keys()
            while (keys.hasNext()) {
                obj.optJSONObject(keys.next())?.let { result += it }
            }
        }
        return result
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
    // GITHUB GIST JSON SOURCE (DIRECT CHANNELS OR MULTI-PROVIDER MANIFEST)
    // -------------------------------------------------------------------------

    data class GistManifestResolution(
        val providers: List<PlaylistSource>,
        val skippedUnsupportedNames: List<String> = emptyList()
    )

    private val rawGistTextCache = ConcurrentHashMap<String, String>()

    /**
     * Downloads a GitHub Gist JSON URL and inspects whether it is a Multi-Provider Manifest
     * (e.g. `{"active_providers": [...]}` / `{"providers": [...]}` containing Xtream, Stalker, or M3U entries).
     * Returns `null` when the Gist is a direct channel/stream JSON or M3U playlist instead.
     */
    suspend fun resolveGistProvidersIfManifest(source: PlaylistSource): GistManifestResolution? =
        withContext(Dispatchers.IO) {
            val rawUrl = resolveGistRawUrl(source.m3uUrl)
            val body = downloadText(rawUrl).trim()
            rawGistTextCache[source.id] = body

            if (body.isEmpty() || body.startsWith("#EXTM3U", ignoreCase = true) || body.contains("#EXTINF:")) {
                return@withContext null
            }

            parseGistProvidersManifest(source, body)
        }

    private fun parseGistProvidersManifest(
        parentSource: PlaylistSource,
        jsonText: String
    ): GistManifestResolution? {
        val providerArray = extractProviderManifestArray(jsonText) ?: return null
        val providers = mutableListOf<PlaylistSource>()
        val skippedUnsupported = mutableListOf<String>()
        val urlHash = parentSource.m3uUrl.trim().hashCode().toUInt().toString(16)

        for (i in 0 until providerArray.length()) {
            val obj = providerArray.optJSONObject(i) ?: continue
            val name = obj.optCleanString("name")
                .ifBlank { obj.optCleanString("title") }
                .ifBlank { "Gist Provider ${i + 1}" }
            val rawType = obj.optCleanString("type").lowercase(Locale.US)
            val host = obj.optCleanString("host")
                .ifBlank { obj.optCleanString("portal") }
                .ifBlank { obj.optCleanString("portal_url") }
                .ifBlank { obj.optCleanString("server") }
                .ifBlank { obj.optCleanString("url") }
            val username = obj.optCleanString("username").ifBlank { obj.optCleanString("user") }
            val password = obj.optCleanString("password").ifBlank { obj.optCleanString("pass") }
            val mac = obj.optCleanString("mac").ifBlank { obj.optCleanString("mac_address") }
            val sn = obj.optCleanString("sn").ifBlank { obj.optCleanString("serial_number") }
            val dev1 = obj.optCleanString("device_id").ifBlank { obj.optCleanString("device_id1") }
            val dev2 = obj.optCleanString("device_id2")
            val signature = obj.optCleanString("signature")
            val m3uUrl = obj.optCleanString("m3u_url")
                .ifBlank { obj.optCleanString("m3u") }
                .ifBlank { obj.optCleanString("url") }
                .ifBlank { obj.optCleanString("link") }
            val epgUrl = obj.optCleanString("epg_url").ifBlank { obj.optCleanString("epg") }

            val slug = name.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "p$i" }
            val stableId = "gist_${urlHash}_${i}_$slug"

            when {
                rawType.contains("stremio") || m3uUrl.contains("manifest.json", ignoreCase = true) -> {
                    skippedUnsupported += "$name (Stremio addon)"
                }
                rawType.contains("xtream") || (host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) -> {
                    if (host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                        providers += PlaylistSource(
                            id = stableId,
                            name = name,
                            type = SourceType.XTREAM,
                            portalUrl = host,
                            username = username,
                            password = password,
                            epgUrl = epgUrl,
                            isActive = true
                        )
                    }
                }
                rawType.contains("stalker") || rawType.contains("mag") || rawType == "mac" ||
                    rawType.contains("ministra") || rawType == "stb" || (host.isNotBlank() && mac.isNotBlank()) -> {
                    if (host.isNotBlank() && mac.isNotBlank()) {
                        providers += PlaylistSource(
                            id = stableId,
                            name = name,
                            type = SourceType.STALKER,
                            portalUrl = host,
                            macAddress = mac,
                            serialNumber = sn,
                            deviceId1 = dev1,
                            deviceId2 = dev2,
                            signature = signature,
                            epgUrl = epgUrl,
                            isActive = true
                        )
                    }
                }
                rawType.contains("m3u") || rawType.contains("playlist") ||
                    (m3uUrl.isNotBlank() && (m3uUrl.contains(".m3u", ignoreCase = true) || m3uUrl.contains("get.php", ignoreCase = true))) -> {
                    if (m3uUrl.isNotBlank()) {
                        providers += PlaylistSource(
                            id = stableId,
                            name = name,
                            type = SourceType.M3U_URL,
                            m3uUrl = m3uUrl,
                            epgUrl = epgUrl,
                            isActive = true
                        )
                    }
                }
                else -> {
                    if (rawType.isNotBlank()) {
                        skippedUnsupported += "$name ($rawType)"
                    }
                }
            }
        }

        return if (providers.isNotEmpty() || skippedUnsupported.isNotEmpty()) {
            GistManifestResolution(
                providers = providers,
                skippedUnsupportedNames = skippedUnsupported
            )
        } else {
            null
        }
    }

    private fun extractProviderManifestArray(jsonText: String): JSONArray? {
        val trimmed = jsonText.trim()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
            if (arr.length() == 0) return null
            val first = arr.optJSONObject(0) ?: return null
            val looksLikeProvider = first.has("host") || first.has("username") || first.has("mac") ||
                first.has("mac_address") || first.has("portal") ||
                first.optCleanString("type").lowercase(Locale.US) in setOf(
                    "xtream", "xtream_codes", "stalker", "mag", "mac", "m3u", "m3u8", "stremio"
                )
            return if (looksLikeProvider) arr else null
        }

        if (!trimmed.startsWith("{")) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val manifestKeys = listOf(
            "active_providers",
            "providers",
            "iptv_providers",
            "servers",
            "playlists",
            "accounts",
            "portals"
        )
        for (key in manifestKeys) {
            val arr = root.optJSONArray(key)
            if (arr != null && arr.length() > 0) {
                return arr
            }
        }
        return null
    }

    private suspend fun fetchGistJsonCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        val body = rawGistTextCache.remove(source.id) ?: run {
            val rawUrl = resolveGistRawUrl(source.m3uUrl)
            downloadText(rawUrl).trim()
        }

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

    private fun formatUnixDate(epochSeconds: Long): String {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(epochSeconds * 1000L))
        }.getOrDefault("")
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