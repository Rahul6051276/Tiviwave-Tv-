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
import org.json.JSONArray
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

        private val STB_PROFILES = listOf(
            MAG250_USER_AGENT to MAG250_X_USER_AGENT,
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3" to "Model: MAG254; Link: Ethernet"
        )

        private const val MAX_PAGES_PER_CATEGORY = 8
        private const val FALLBACK_PROBE_PAGES = 3
        private const val FAST_CONNECT_TIMEOUT_SEC = 8L
        private const val FAST_READ_TIMEOUT_SEC = 12L
        private const val LIST_CONNECT_TIMEOUT_SEC = 12L
        private const val LIST_READ_TIMEOUT_SEC = 25L
        private const val SESSION_TTL_MS = 30 * 60_000L
    }

    private data class StalkerSession(
        val endpointUrl: String,
        val token: String,
        val random: String = "",
        val encodeMacCookie: Boolean = false,
        val profileIndex: Int = 0,
        val useJsHttpRequest: Boolean = true,
        val sessionCookies: Map<String, String> = emptyMap(),
        val profileError: String? = null,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private data class RawHttpResult(
        val body: String,
        val setCookies: Map<String, String>,
        val finalUrl: String
    )

    private data class CategoryFetchOutcome(
        val categories: List<ContentCategory>,
        val lastError: String? = null
    )

    private val sessionCache = ConcurrentHashMap<String, StalkerSession>()

    /**
     * Caches parsed items discovered during Step-1 stream fallback (keyed by "sourceId:CONTENT_TYPE")
     * so Step-2 selective import can immediately deliver items for fallback-grouped categories.
     */
    private val fallbackItemBuffer = ConcurrentHashMap<String, List<MediaItem>>()

    private fun normalizeMacAddress(rawMac: String): String {
        val cleaned = rawMac.trim().uppercase(Locale.US).replace('-', ':')
        val hexOnly = cleaned.replace(":", "")
        return if (hexOnly.length == 12 && hexOnly.all { it in '0'..'9' || it in 'A'..'F' }) {
            hexOnly.chunked(2).joinToString(":")
        } else {
            cleaned
        }
    }

    private fun buildSessionCacheKey(source: PlaylistSource): String {
        return listOf(
            normalizePortalBase(source.portalUrl).lowercase(Locale.US),
            normalizeMacAddress(source.macAddress),
            source.serialNumber.trim(),
            source.deviceId1.trim(),
            source.deviceId2.trim()
        ).joinToString("|")
    }

    private fun buildCookieHeader(
        source: PlaylistSource,
        encodeMac: Boolean = false,
        extraCookies: Map<String, String> = emptyMap()
    ): String {
        val normalizedMac = normalizeMacAddress(source.macAddress)
        val macValue = if (encodeMac) URLEncoder.encode(normalizedMac, "UTF-8") else normalizedMac
        val timezone = TimeZone.getDefault().id.ifBlank { "GMT" }
        val cookieMap = linkedMapOf(
            "mac" to macValue,
            "stb_lang" to "en",
            "timezone" to timezone
        )
        extraCookies.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && k.lowercase(Locale.US) != "mac") {
                cookieMap[k] = v
            }
        }
        return cookieMap.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    fun buildStalkerHeaders(
        source: PlaylistSource,
        token: String? = null,
        encodeMacCookie: Boolean = false,
        profileIndex: Int = 0,
        extraCookies: Map<String, String> = emptyMap(),
        endpointOverride: String? = null
    ): Map<String, String> {
        val refererBase = normalizePortalBase(endpointOverride ?: source.portalUrl).trimEnd('/') + "/c/"
        val (ua, xUa) = STB_PROFILES[profileIndex.coerceIn(0, STB_PROFILES.lastIndex)]
        return buildMap {
            put("User-Agent", ua)
            put("X-User-Agent", xUa)
            put("Cookie", buildCookieHeader(source, encodeMacCookie, extraCookies))
            put("Referer", refererBase)
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9")
            put("Connection", "Keep-Alive")
            if (!token.isNullOrBlank()) {
                put("Authorization", "Bearer $token")
            }
        }
    }

    /**
     * Normalizes Stalker portal base URL cleanly from `//host/...`, `/c`, `/c/`, `/c/index.html`,
     * `/portal.php`, `/server/load.php`, or root URLs.
     */
    fun normalizePortalBase(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (clean.isBlank()) return ""
        clean = when {
            clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "http:$clean"
            else -> "http://${clean.trimStart('/')}"
        }
        clean = clean.substringBefore('#').substringBefore('?').trim()

        val suffixes = listOf(
            "/c/index.html",
            "/index.html",
            "/index.php",
            "/server/load.php",
            "/load.php",
            "/c/portal.php",
            "/portal.php",
            "/c"
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

    private fun swapScheme(url: String): String? {
        return when {
            url.startsWith("http://", ignoreCase = true) -> "https://${url.substring(7)}"
            url.startsWith("https://", ignoreCase = true) -> "http://${url.substring(8)}"
            else -> null
        }
    }

    private fun candidateEndpoints(rawUrl: String): List<String> {
        val trimmed = rawUrl.trim()
        val base = normalizePortalBase(trimmed)
        if (base.isBlank()) return emptyList()
        val origin = extractOrigin(base)
        val candidates = linkedSetOf<String>()

        val explicitClean = trimmed.substringBefore('#').substringBefore('?').trim()
        if (explicitClean.contains("portal.php", ignoreCase = true) ||
            explicitClean.contains("load.php", ignoreCase = true)
        ) {
            val withScheme = when {
                explicitClean.startsWith("http://", ignoreCase = true) ||
                    explicitClean.startsWith("https://", ignoreCase = true) -> explicitClean
                explicitClean.startsWith("//") -> "http:$explicitClean"
                else -> "http://${explicitClean.trimStart('/')}"
            }
            candidates.add(withScheme.trimEnd('/'))
        }

        fun addForBase(b: String, o: String) {
            if (b.endsWith("/stalker_portal", ignoreCase = true) ||
                b.endsWith("/ministra", ignoreCase = true) ||
                b.endsWith("/portal", ignoreCase = true)
            ) {
                candidates.add("$b/server/load.php")
                candidates.add("$b/portal.php")
                if (o != b) {
                    candidates.add("$o/portal.php")
                    candidates.add("$o/server/load.php")
                }
            } else {
                candidates.add("$b/portal.php")
                candidates.add("$b/server/load.php")
                candidates.add("$b/stalker_portal/server/load.php")
                candidates.add("$b/c/portal.php")
                candidates.add("$b/c/server/load.php")
                if (o != b) {
                    candidates.add("$o/portal.php")
                    candidates.add("$o/server/load.php")
                    candidates.add("$o/stalker_portal/server/load.php")
                }
            }
        }

        addForBase(base, origin)

        // Automatic HTTP <-> HTTPS scheme fallback for portals that require the alternate scheme
        val altBase = swapScheme(base)
        if (altBase != null) {
            val altOrigin = extractOrigin(altBase)
            addForBase(altBase, altOrigin)
        }

        return candidates.toList()
    }

    private fun executeRequestRaw(
        endpointUrl: String,
        params: Map<String, String>,
        source: PlaylistSource,
        token: String? = null,
        encodeMacCookie: Boolean = false,
        profileIndex: Int = 0,
        useJsHttpRequest: Boolean = true,
        extraCookies: Map<String, String> = emptyMap(),
        connectTimeoutSec: Long = FAST_CONNECT_TIMEOUT_SEC,
        readTimeoutSec: Long = FAST_READ_TIMEOUT_SEC
    ): RawHttpResult {
        val urlBuilder = endpointUrl.toHttpUrlOrNull()?.newBuilder()
            ?: error("Invalid Stalker portal URL: $endpointUrl")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        if (useJsHttpRequest) {
            urlBuilder.addQueryParameter("JsHttpRequest", "1-xml")
        }

        val headers = buildStalkerHeaders(
            source = source,
            token = token,
            encodeMacCookie = encodeMacCookie,
            profileIndex = profileIndex,
            extraCookies = extraCookies,
            endpointOverride = endpointUrl
        )
        val client = RoutingManager.buildHttpClient(
            customHeaders = headers,
            connectTimeoutSec = connectTimeoutSec,
            readTimeoutSec = readTimeoutSec
        )
        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val cookies = mutableMapOf<String, String>()
            response.headers("Set-Cookie").forEach { rawCookie ->
                val pair = rawCookie.substringBefore(';').trim()
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0) {
                    val ck = pair.substring(0, eqIdx).trim()
                    val cv = pair.substring(eqIdx + 1).trim()
                    if (ck.isNotBlank() && cv.isNotBlank() && !cv.equals("deleted", ignoreCase = true)) {
                        cookies[ck] = cv
                    }
                }
            }
            if (!response.isSuccessful) {
                val isCloudflare = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    response.header("CF-RAY") != null ||
                    body.contains("cloudflare", ignoreCase = true)
                val hint = when {
                    isCloudflare && response.code == 403 ->
                        "blocked by Cloudflare/geo-filter — try a Country/Proxy node or different network"
                    response.code == 401 || response.code == 403 ->
                        "access denied / unauthorized MAC"
                    response.code == 404 -> "endpoint not found"
                    response.code in 500..599 -> "portal server error"
                    else -> "HTTP error"
                }
                error("Stalker portal HTTP ${response.code} ($hint)")
            }
            return RawHttpResult(
                body = body,
                setCookies = cookies,
                finalUrl = response.request.url.toString()
            )
        }
    }

    private fun executeSessionRequest(
        session: StalkerSession,
        params: Map<String, String>,
        source: PlaylistSource,
        readTimeoutSec: Long = LIST_READ_TIMEOUT_SEC
    ): String {
        val result = executeRequestRaw(
            endpointUrl = session.endpointUrl,
            params = params,
            source = source,
            token = session.token,
            encodeMacCookie = session.encodeMacCookie,
            profileIndex = session.profileIndex,
            useJsHttpRequest = session.useJsHttpRequest,
            extraCookies = session.sessionCookies,
            connectTimeoutSec = LIST_CONNECT_TIMEOUT_SEC,
            readTimeoutSec = readTimeoutSec
        )
        if (result.body.contains("Authorization failed", ignoreCase = true)) {
            // Some portals expect `token` as a query parameter in addition to Bearer header
            val retry = executeRequestRaw(
                endpointUrl = session.endpointUrl,
                params = params + ("token" to session.token),
                source = source,
                token = session.token,
                encodeMacCookie = session.encodeMacCookie,
                profileIndex = session.profileIndex,
                useJsHttpRequest = session.useJsHttpRequest,
                extraCookies = session.sessionCookies + result.setCookies,
                connectTimeoutSec = LIST_CONNECT_TIMEOUT_SEC,
                readTimeoutSec = readTimeoutSec
            )
            return retry.body
        }
        return result.body
    }

    /**
     * Discovers any redirected portal host/path (for short/vanity Stalker URLs that 301/302 redirect).
     */
    private fun probeRedirectedBase(source: PlaylistSource): String? {
        val base = normalizePortalBase(source.portalUrl)
        if (base.isBlank()) return null
        return runCatching {
            val res = executeRequestRaw(
                endpointUrl = "$base/c/",
                params = emptyMap(),
                source = source,
                token = null,
                useJsHttpRequest = false,
                connectTimeoutSec = 6L,
                readTimeoutSec = 6L
            )
            val redirectedBase = normalizePortalBase(res.finalUrl)
            if (redirectedBase.isNotBlank() && !redirectedBase.equals(base, ignoreCase = true)) {
                redirectedBase
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Performs universal Stalker handshake across normalized standard endpoints (`portal.php`,
     * `server/load.php`, `stalker_portal/server/load.php`), HTTP/HTTPS fallback, redirect discovery,
     * raw vs URL-encoded MAC cookies, and MAG250/MAG254 headers so any valid portal connects reliably.
     */
    private suspend fun ensureSession(
        source: PlaylistSource,
        forceRefresh: Boolean = false,
        excludedEndpoints: Set<String> = emptySet()
    ): StalkerSession = withContext(Dispatchers.IO) {
        val base = normalizePortalBase(source.portalUrl)
        if (base.isBlank()) {
            error("Stalker portal URL is required.")
        }
        val normalizedMac = normalizeMacAddress(source.macAddress)
        if (normalizedMac.isBlank()) {
            error("Stalker MAC address is required (e.g., 00:1A:79:XX:XX:XX).")
        }

        val cacheKey = buildSessionCacheKey(source)
        val cached = sessionCache[cacheKey]
        if (!forceRefresh &&
            cached != null &&
            cached.endpointUrl !in excludedEndpoints &&
            System.currentTimeMillis() - cached.createdAtMs < SESSION_TTL_MS
        ) {
            return@withContext cached
        }

        val initialEndpoints = candidateEndpoints(source.portalUrl).filterNot { it in excludedEndpoints }
        var lastError: Exception? = null

        fun tryEndpoints(endpoints: List<String>): StalkerSession? {
            for (endpoint in endpoints) {
                try {
                    val handshake = performHandshake(endpoint, source) ?: continue
                    val verified = verifyAndProbeLoadEndpoint(
                        handshakeSession = handshake,
                        allEndpoints = endpoints,
                        source = source
                    )
                    return verified
                } catch (e: Exception) {
                    lastError = e
                }
            }
            return null
        }

        val primaryMatch = tryEndpoints(initialEndpoints)
        if (primaryMatch != null) {
            sessionCache[cacheKey] = primaryMatch
            return@withContext primaryMatch
        }

        // Fallback: Check if portal vanity URL redirects to a different host/path
        val redirectedBase = probeRedirectedBase(source)
        if (redirectedBase != null) {
            val redirectedEndpoints = candidateEndpoints(redirectedBase)
                .filterNot { it in excludedEndpoints || it in initialEndpoints }
            val redirectedMatch = tryEndpoints(redirectedEndpoints)
            if (redirectedMatch != null) {
                sessionCache[cacheKey] = redirectedMatch
                return@withContext redirectedMatch
            }
        }

        val reason = lastError?.message?.takeIf { it.isNotBlank() }
            ?: "No valid Stalker handshake response from $base"
        throw IllegalStateException("Stalker connection failed for MAC $normalizedMac: $reason")
    }

    private fun performHandshake(endpoint: String, source: PlaylistSource): StalkerSession? {
        data class AttemptConfig(
            val params: Map<String, String>,
            val encodeMac: Boolean,
            val profileIdx: Int,
            val useJsHttp: Boolean
        )

        val attempts = listOf(
            // Fast path: Standard MAG250 + raw MAC + prehash=0
            AttemptConfig(mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), false, 0, true),
            // Variant 2: URL-encoded MAC (required by some PHP/Nginx Stalker bridges)
            AttemptConfig(mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), true, 0, true),
            // Variant 3: Without prehash & without JsHttpRequest
            AttemptConfig(mapOf("type" to "stb", "action" to "handshake", "token" to ""), false, 0, false),
            // Variant 4: MAG254 STB profile
            AttemptConfig(mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), true, 1, true)
        )

        var lastException: Exception? = null
        for (attempt in attempts) {
            try {
                val res = executeRequestRaw(
                    endpointUrl = endpoint,
                    params = attempt.params,
                    source = source,
                    token = null,
                    encodeMacCookie = attempt.encodeMac,
                    profileIndex = attempt.profileIdx,
                    useJsHttpRequest = attempt.useJsHttp
                )
                val json = parseJsonSafely(res.body) ?: continue
                val jsObj = json.optJSONObject("js")
                val token = jsObj?.optCleanString("token").orEmpty()
                    .ifBlank { json.optCleanString("token") }
                val random = jsObj?.optCleanString("random").orEmpty()
                if (token.isNotBlank()) {
                    return StalkerSession(
                        endpointUrl = endpoint,
                        token = token,
                        random = random,
                        encodeMacCookie = attempt.encodeMac,
                        profileIndex = attempt.profileIdx,
                        useJsHttpRequest = attempt.useJsHttp,
                        sessionCookies = res.setCookies
                    )
                }
            } catch (e: Exception) {
                lastException = e
                // If endpoint is a clear 404 (not found), skip trying other variants on this same missing path
                if (e.message?.contains("HTTP 404") == true) {
                    throw e
                }
            }
        }
        if (lastException != null) throw lastException
        return null
    }

    private fun verifyAndProbeLoadEndpoint(
        handshakeSession: StalkerSession,
        allEndpoints: List<String>,
        source: PlaylistSource
    ): StalkerSession {
        val normalizedMac = normalizeMacAddress(source.macAddress)
        val profileParams = buildMap {
            put("type", "stb")
            put("action", "get_profile")
            put("hd", "1")
            put(
                "ver",
                "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
            )
            put("num_banks", "2")
            put("stb_type", if (handshakeSession.profileIndex == 0) "MAG250" else "MAG254")
            put("image_version", "218")
            put("auth_second_step", "1")
            put("hw_version", "1.7-BD-00")
            put("not_valid_token", "0")
            if (handshakeSession.random.isNotBlank()) put("random", handshakeSession.random)
            put(
                "metrics",
                "{\"mac\":\"$normalizedMac\",\"sn\":\"${source.serialNumber.trim()}\",\"model\":\"MAG250\",\"type\":\"STB\"}"
            )
            if (source.serialNumber.isNotBlank()) put("sn", source.serialNumber.trim())
            if (source.deviceId1.isNotBlank()) put("device_id", source.deviceId1.trim())
            if (source.deviceId2.isNotBlank()) put("device_id2", source.deviceId2.trim())
            if (source.signature.isNotBlank()) put("signature", source.signature.trim())
        }

        val probeOrder = linkedSetOf(handshakeSession.endpointUrl)
        allEndpoints.forEach { ep ->
            if (ep.endsWith("/server/load.php", ignoreCase = true) ||
                ep.endsWith("/portal.php", ignoreCase = true)
            ) {
                probeOrder.add(ep)
            }
        }

        var profileError: String? = null
        for (probeEndpoint in probeOrder) {
            val res = runCatching {
                executeRequestRaw(
                    endpointUrl = probeEndpoint,
                    params = profileParams,
                    source = source,
                    token = handshakeSession.token,
                    encodeMacCookie = handshakeSession.encodeMacCookie,
                    profileIndex = handshakeSession.profileIndex,
                    useJsHttpRequest = handshakeSession.useJsHttpRequest,
                    extraCookies = handshakeSession.sessionCookies
                )
            }.getOrNull() ?: continue

            val json = parseJsonSafely(res.body) ?: continue
            val jsObj = json.optJSONObject("js")
            val updatedToken = jsObj?.optCleanString("token").orEmpty().ifBlank { handshakeSession.token }
            val explicitMsg = jsObj?.optCleanString("msg").orEmpty()
                .ifBlank { jsObj?.optCleanString("message").orEmpty() }
                .ifBlank { json.optCleanString("msg") }
            val blockedFlag = jsObj?.opt("blocked")?.toString()?.trim()?.lowercase(Locale.US)
            val statusStr = jsObj?.optCleanString("status").orEmpty().lowercase(Locale.US)

            if (blockedFlag == "1" || blockedFlag == "true" ||
                statusStr == "blocked" || statusStr == "expired" || statusStr == "disabled"
            ) {
                profileError = explicitMsg.ifBlank {
                    "Portal reported account status '${statusStr.ifBlank { "blocked" }}' for MAC $normalizedMac."
                }
            } else if (explicitMsg.contains("blocked", ignoreCase = true) ||
                explicitMsg.contains("expired", ignoreCase = true) ||
                explicitMsg.contains("not found", ignoreCase = true) ||
                explicitMsg.contains("denied", ignoreCase = true)
            ) {
                profileError = explicitMsg
            }

            return handshakeSession.copy(
                endpointUrl = probeEndpoint,
                token = updatedToken,
                sessionCookies = handshakeSession.sessionCookies + res.setCookies,
                profileError = profileError
            )
        }

        return handshakeSession.copy(profileError = profileError)
    }

    /**
     * Step 1 Metadata-Only Fetch:
     * Fetches Live TV (`itv`), VOD (`vod`), and Series (`series`) categories using both
     * `get_genres` and `get_categories` (parsing both JSON arrays and JSON object maps inside `js`).
     * Automatically falls back to stream endpoints (`get_ordered_list` / `get_all_channels`) if
     * category endpoints return empty, grouping items by category/genre or creating default categories.
     */
    suspend fun fetchAllCategories(source: PlaylistSource): List<ContentCategory> = withContext(Dispatchers.IO) {
        clearFallbackBufferForSource(source.id)

        val triedEndpoints = mutableSetOf<String>()
        var session = ensureSession(source, forceRefresh = false)
        triedEndpoints += session.endpointUrl

        var outcome = fetchCategoriesWithSession(source, session)
        if (outcome.categories.isNotEmpty()) {
            return@withContext outcome.categories
        }

        // Token may have expired: clear cache and retry once with a fresh handshake
        sessionCache.remove(buildSessionCacheKey(source))
        session = ensureSession(source, forceRefresh = true)
        outcome = fetchCategoriesWithSession(source, session)
        if (outcome.categories.isNotEmpty()) {
            return@withContext outcome.categories
        }

        // Probe any remaining standard load endpoints (server/load.php <-> portal.php) if primary returned empty
        val remainingEndpoints = candidateEndpoints(source.portalUrl).filterNot { it in triedEndpoints }
        for (altEndpoint in remainingEndpoints) {
            val altSession = runCatching {
                val hs = performHandshake(altEndpoint, source) ?: return@runCatching null
                verifyAndProbeLoadEndpoint(
                    handshakeSession = hs,
                    allEndpoints = remainingEndpoints,
                    source = source
                )
            }.getOrNull() ?: continue

            triedEndpoints += altSession.endpointUrl
            val altOutcome = fetchCategoriesWithSession(source, altSession)
            if (altOutcome.categories.isNotEmpty()) {
                sessionCache[buildSessionCacheKey(source)] = altSession
                return@withContext altOutcome.categories
            }
            if (outcome.lastError.isNullOrBlank() && !altOutcome.lastError.isNullOrBlank()) {
                outcome = altOutcome
            }
        }

        val mac = normalizeMacAddress(source.macAddress)
        val detail = session.profileError
            ?: outcome.lastError
            ?: "Portal at ${session.endpointUrl} returned 0 Live, VOD, and Series categories or streams. Verify MAC $mac is active."
        throw IllegalStateException(detail)
    }

    private fun clearFallbackBufferForSource(sourceId: String) {
        ContentType.entries.forEach { type ->
            fallbackItemBuffer.remove("${sourceId}:${type.name}")
        }
    }

    private fun fetchCategoriesWithSession(
        source: PlaylistSource,
        session: StalkerSession
    ): CategoryFetchOutcome {
        val categories = mutableListOf<ContentCategory>()
        var lastError: String? = null

        val specs = listOf(
            Triple(ContentType.LIVE, "itv", listOf("get_genres", "get_categories")),
            Triple(ContentType.VOD, "vod", listOf("get_categories", "get_genres")),
            Triple(ContentType.SERIES, "series", listOf("get_categories", "get_genres"))
        )

        for ((contentType, stalkerType, actions) in specs) {
            var typeCategories = emptyList<ContentCategory>()

            for (action in actions) {
                val response = runCatching {
                    executeSessionRequest(
                        session = session,
                        params = mapOf("type" to stalkerType, "action" to action),
                        source = source
                    )
                }.onFailure { err ->
                    if (lastError.isNullOrBlank()) lastError = err.message
                }.getOrNull() ?: continue

                val parsed = parseCategoriesFlexible(response, source.id, contentType)
                if (parsed.isNotEmpty()) {
                    typeCategories = parsed
                    break
                }
            }

            // Fallback to streams if category/genre endpoints returned empty
            if (typeCategories.isEmpty()) {
                val fallbackCategories = fetchFallbackCategoriesFromStreams(
                    source = source,
                    session = session,
                    contentType = contentType,
                    stalkerType = stalkerType,
                    onError = { errMsg -> if (lastError.isNullOrBlank()) lastError = errMsg }
                )
                if (fallbackCategories.isNotEmpty()) {
                    typeCategories = fallbackCategories
                }
            }

            categories += typeCategories
        }

        return CategoryFetchOutcome(
            categories = categories.distinctBy { "${it.type.name}:${it.id}" },
            lastError = lastError
        )
    }

    /**
     * Parses Stalker category/genre responses supporting JSONArray, JSONObject maps, nested data/categories/genres,
     * and top-level JSONArray responses.
     */
    private fun parseCategoriesFlexible(
        rawBody: String,
        sourceId: String,
        type: ContentType
    ): List<ContentCategory> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rawPairs = mutableListOf<Pair<String, String>>()

        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            collectCategoryPairsFromArray(arr, rawPairs)
        } else {
            val json = parseJsonSafely(trimmed) ?: return emptyList()
            val jsVal = json.opt("js")
            when (jsVal) {
                is JSONArray -> collectCategoryPairsFromArray(jsVal, rawPairs)
                is JSONObject -> {
                    val nestedArray = jsVal.optJSONArray("data")
                        ?: jsVal.optJSONArray("categories")
                        ?: jsVal.optJSONArray("genres")
                    if (nestedArray != null) {
                        collectCategoryPairsFromArray(nestedArray, rawPairs)
                    } else {
                        collectCategoryPairsFromObjectMap(jsVal, rawPairs)
                    }
                }
            }
        }

        val result = mutableListOf<ContentCategory>()
        val seenIds = mutableSetOf<String>()
        for ((rawId, rawTitle) in rawPairs) {
            val id = rawId.trim()
            val title = rawTitle.trim()
            if (id.isBlank() || id == "*" || title.isBlank() || title.equals("All", ignoreCase = true)) {
                continue
            }
            val compositeId = "${type.name.lowercase()}_$id"
            if (seenIds.add(compositeId)) {
                result += ContentCategory(
                    id = compositeId,
                    sourceId = sourceId,
                    name = title,
                    countryGroup = CountryCategoryClassifier.classifyCountryGroup(title),
                    type = type,
                    isSelected = false
                )
            }
        }
        return result
    }

    private fun collectCategoryPairsFromArray(array: JSONArray, out: MutableList<Pair<String, String>>) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optCleanString("id")
                .ifBlank { obj.optCleanString("category_id") }
                .ifBlank { obj.optCleanString("genre_id") }
            val title = obj.optCleanString("title")
                .ifBlank { obj.optCleanString("name") }
                .ifBlank { obj.optCleanString("category_name") }
                .ifBlank { obj.optCleanString("alias") }
            if (id.isNotBlank() && title.isNotBlank()) {
                out += id to title
            }
        }
    }

    private fun collectCategoryPairsFromObjectMap(mapObj: JSONObject, out: MutableList<Pair<String, String>>) {
        val ignoredMetaKeys = setOf("total_items", "max_page_items", "selected_item", "cur_page", "error", "msg")
        val keys = mapObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.lowercase(Locale.US) in ignoredMetaKeys) continue
            val value = mapObj.opt(key) ?: continue
            when (value) {
                is JSONObject -> {
                    val id = value.optCleanString("id")
                        .ifBlank { value.optCleanString("category_id") }
                        .ifBlank { value.optCleanString("genre_id") }
                        .ifBlank { key }
                    val title = value.optCleanString("title")
                        .ifBlank { value.optCleanString("name") }
                        .ifBlank { value.optCleanString("category_name") }
                        .ifBlank { value.optCleanString("alias") }
                    if (id.isNotBlank() && title.isNotBlank()) {
                        out += id to title
                    }
                }
                is String -> {
                    val cleanTitle = value.trim()
                    if (cleanTitle.isNotBlank() &&
                        !cleanTitle.equals("null", ignoreCase = true) &&
                        !cleanTitle.equals("false", ignoreCase = true)
                    ) {
                        out += key to cleanTitle
                    }
                }
            }
        }
    }

    /**
     * Fallback when Stalker category/genre endpoints return empty:
     * Fetches streams via `get_ordered_list` (and `get_all_channels` for Live TV),
     * groups them by `category_id` / `genre_id` / `tv_genre_id` / `category_title`,
     * or falls back to a default `"All Channels"` / `"All VOD"` / `"All Series"` category.
     */
    private fun fetchFallbackCategoriesFromStreams(
        source: PlaylistSource,
        session: StalkerSession,
        contentType: ContentType,
        stalkerType: String,
        onError: (String) -> Unit
    ): List<ContentCategory> {
        val rawItems = mutableListOf<JSONObject>()
        val filterParamKey = if (contentType == ContentType.LIVE) "genre" else "category"

        val probeVariants = buildList {
            if (contentType == ContentType.LIVE) {
                add(mapOf("type" to stalkerType, "action" to "get_ordered_list", filterParamKey to "*", "sortby" to "number"))
                add(mapOf("type" to stalkerType, "action" to "get_ordered_list"))
            } else {
                add(mapOf("type" to stalkerType, "action" to "get_ordered_list", filterParamKey to "*", "sortby" to "added"))
                add(mapOf("type" to stalkerType, "action" to "get_ordered_list", filterParamKey to "*"))
                add(mapOf("type" to stalkerType, "action" to "get_ordered_list"))
            }
        }

        for (baseParams in probeVariants) {
            var page = 1
            while (page <= FALLBACK_PROBE_PAGES) {
                val raw = runCatching {
                    executeSessionRequest(
                        session = session,
                        params = baseParams + ("p" to page.toString()),
                        source = source
                    )
                }.onFailure { err ->
                    err.message?.let(onError)
                }.getOrNull() ?: break

                val pageObjects = extractStalkerItemObjects(raw)
                if (pageObjects.isEmpty()) break
                rawItems += pageObjects

                val jsObj = parseJsonSafely(raw)?.optJSONObject("js")
                val totalItems = jsObj?.optInt("total_items", 0) ?: 0
                val maxPageItems = jsObj?.optInt("max_page_items", 14)?.coerceAtLeast(1) ?: 14
                if (pageObjects.size < maxPageItems || (totalItems > 0 && rawItems.size >= totalItems)) {
                    break
                }
                page++
            }
            if (rawItems.isNotEmpty()) break
        }

        if (rawItems.isEmpty() && contentType == ContentType.LIVE) {
            val allRaw = runCatching {
                executeSessionRequest(
                    session = session,
                    params = mapOf("type" to "itv", "action" to "get_all_channels"),
                    source = source
                )
            }.onFailure { err ->
                err.message?.let(onError)
            }.getOrNull()
            if (!allRaw.isNullOrBlank()) {
                rawItems += extractStalkerItemObjects(allRaw)
            }
        }

        if (rawItems.isEmpty()) return emptyList()

        val defaultCatName = when (contentType) {
            ContentType.LIVE -> "All Channels"
            ContentType.VOD -> "All VOD"
            ContentType.SERIES -> "All Series"
        }
        val defaultCatId = "${contentType.name.lowercase()}_*"

        val categoryMap = linkedMapOf<String, ContentCategory>()
        val countMap = mutableMapOf<String, Int>()
        val parsedItems = mutableListOf<MediaItem>()

        for (obj in rawItems) {
            val rawCatId = when (contentType) {
                ContentType.LIVE -> obj.optCleanString("tv_genre_id")
                    .ifBlank { obj.optCleanString("genre_id") }
                    .ifBlank { obj.optCleanString("category_id") }
                ContentType.VOD,
                ContentType.SERIES -> obj.optCleanString("category_id")
                    .ifBlank { obj.optCleanString("genre_id") }
                    .ifBlank { obj.optCleanString("tv_genre_id") }
            }
            val rawCatTitle = obj.optCleanString("category_title")
                .ifBlank { obj.optCleanString("genre_title") }
                .ifBlank { obj.optCleanString("category_name") }
                .ifBlank { obj.optCleanString("genre_name") }
                .ifBlank { obj.optCleanString("group_title") }

            val hasUsableId = rawCatId.isNotBlank() && rawCatId != "*" &&
                (rawCatId != "0" || rawCatTitle.isNotBlank())
            val hasUsableTitle = rawCatTitle.isNotBlank() && !rawCatTitle.equals("All", ignoreCase = true)

            val (catId, catName) = when {
                hasUsableId -> {
                    val labelPrefix = when (contentType) {
                        ContentType.LIVE -> "Channels Group"
                        ContentType.VOD -> "VOD Category"
                        ContentType.SERIES -> "Series Category"
                    }
                    "${contentType.name.lowercase()}_$rawCatId" to
                        (if (hasUsableTitle) rawCatTitle else "$labelPrefix $rawCatId")
                }
                hasUsableTitle -> {
                    val slug = rawCatTitle.lowercase(Locale.US)
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                        .ifBlank { "default" }
                    "${contentType.name.lowercase()}_$slug" to rawCatTitle
                }
                else -> defaultCatId to defaultCatName
            }

            val category = categoryMap.getOrPut(catId) {
                ContentCategory(
                    id = catId,
                    sourceId = source.id,
                    name = catName,
                    countryGroup = CountryCategoryClassifier.classifyCountryGroup(catName),
                    type = contentType,
                    isSelected = false
                )
            }

            val parsedItem = parseMediaItem(obj, source, session, category)
            if (parsedItem != null) {
                parsedItems += parsedItem
                countMap[catId] = (countMap[catId] ?: 0) + 1
            }
        }

        if (parsedItems.isEmpty()) return emptyList()

        fallbackItemBuffer["${source.id}:${contentType.name}"] = parsedItems.distinctBy { it.id }

        return categoryMap.values.map { cat ->
            cat.copy(itemCount = countMap[cat.id] ?: 0)
        }
    }

    private fun extractStalkerItemObjects(rawBody: String): List<JSONObject> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<JSONObject>()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { result += it }
            }
            return result
        }

        val json = parseJsonSafely(trimmed) ?: return emptyList()
        val jsVal = json.opt("js")
        when (jsVal) {
            is JSONArray -> {
                for (i in 0 until jsVal.length()) {
                    jsVal.optJSONObject(i)?.let { result += it }
                }
            }
            is JSONObject -> {
                val dataVal = jsVal.opt("data")
                when (dataVal) {
                    is JSONArray -> {
                        for (i in 0 until dataVal.length()) {
                            dataVal.optJSONObject(i)?.let { result += it }
                        }
                    }
                    is JSONObject -> {
                        val keys = dataVal.keys()
                        while (keys.hasNext()) {
                            dataVal.optJSONObject(keys.next())?.let { result += it }
                        }
                    }
                    else -> {
                        val keys = jsVal.keys()
                        while (keys.hasNext()) {
                            jsVal.optJSONObject(keys.next())?.let { obj ->
                                if (obj.has("id") || obj.has("name") || obj.has("cmd")) {
                                    result += obj
                                }
                            }
                        }
                    }
                }
            }
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
        var allLiveChannelsCache: List<JSONObject>? = null

        for (category in selectedCategories) {
            val rawCatId = category.id.substringAfter('_', category.id)
            val isWildcard = rawCatId == "*" || rawCatId.equals("all", ignoreCase = true)
            val stalkerType = when (category.type) {
                ContentType.LIVE -> "itv"
                ContentType.VOD -> "vod"
                ContentType.SERIES -> "series"
            }
            val filterParamKey = if (category.type == ContentType.LIVE) "genre" else "category"
            val sortParam = if (category.type == ContentType.LIVE) "number" else "added"

            val categoryItems = mutableListOf<MediaItem>()
            var page = 1
            var totalExpected = Int.MAX_VALUE
            var fetchedForCategory = 0

            while (page <= MAX_PAGES_PER_CATEGORY && fetchedForCategory < totalExpected) {
                val baseParams = buildMap {
                    put("type", stalkerType)
                    put("action", "get_ordered_list")
                    put(filterParamKey, if (isWildcard) "*" else rawCatId)
                    put("sortby", sortParam)
                    put("p", page.toString())
                }

                var raw = runCatching {
                    executeSessionRequest(session = session, params = baseParams, source = source)
                }.getOrNull()

                var pageObjects = raw?.let { extractStalkerItemObjects(it) }.orEmpty()

                // Some Stalker portals return empty when `sortby=added` is passed for VOD/Series:
                // retry page 1 without `sortby` while still sorting newest-first locally.
                if (page == 1 && pageObjects.isEmpty() && category.type != ContentType.LIVE) {
                    val retryParams = buildMap {
                        put("type", stalkerType)
                        put("action", "get_ordered_list")
                        if (!isWildcard) put(filterParamKey, rawCatId)
                        put("p", "1")
                    }
                    raw = runCatching {
                        executeSessionRequest(session = session, params = retryParams, source = source)
                    }.getOrNull()
                    pageObjects = raw?.let { extractStalkerItemObjects(it) }.orEmpty()
                }

                if (pageObjects.isEmpty()) break

                val jsObj = raw?.let { parseJsonSafely(it)?.optJSONObject("js") }
                val totalItems = jsObj?.optInt("total_items", 0) ?: 0
                if (totalItems > 0) totalExpected = totalItems

                for (obj in pageObjects) {
                    val item = parseMediaItem(obj, source, session, category)
                    if (item != null) {
                        categoryItems += item
                    }
                }

                fetchedForCategory += pageObjects.size
                val maxPageItems = jsObj?.optInt("max_page_items", 14)?.coerceAtLeast(1) ?: 14
                if (pageObjects.size < maxPageItems) break
                page++
            }

            // Fallback 1: Step-1 stream-fallback buffer
            if (categoryItems.isEmpty()) {
                val buffered = fallbackItemBuffer["${source.id}:${category.type.name}"].orEmpty()
                val matched = if (isWildcard) buffered else buffered.filter { it.categoryId == category.id }
                if (matched.isNotEmpty()) {
                    categoryItems += matched
                }
            }

            // Fallback 2 (Live TV cold-restart fallback): fetch `get_all_channels` and filter locally
            if (categoryItems.isEmpty() && category.type == ContentType.LIVE) {
                val allLiveObjs = allLiveChannelsCache ?: run {
                    val allRaw = runCatching {
                        executeSessionRequest(
                            session = session,
                            params = mapOf("type" to "itv", "action" to "get_all_channels"),
                            source = source
                        )
                    }.getOrNull()
                    val parsed = allRaw?.let { extractStalkerItemObjects(it) }.orEmpty()
                    allLiveChannelsCache = parsed
                    parsed
                }
                for (obj in allLiveObjs) {
                    val objGenreId = obj.optCleanString("tv_genre_id")
                        .ifBlank { obj.optCleanString("genre_id") }
                        .ifBlank { obj.optCleanString("category_id") }
                    if (isWildcard || objGenreId == rawCatId) {
                        parseMediaItem(obj, source, session, category)?.let { categoryItems += it }
                    }
                }
            }

            imported += categoryItems
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

        val assetBase = session.endpointUrl.ifBlank { source.portalUrl }
        val resolvedPoster = resolvePortalAssetUrl(assetBase, rawPoster)
        val resolvedBackdrop = resolvePortalAssetUrl(assetBase, rawBackdrop)

        val number = obj.optCleanString("number").toIntOrNull() ?: 0
        val rating = normalizeRating(
            obj.optCleanString("rating_imdb")
                .ifBlank { obj.optCleanString("rating_kinopoisk") }
                .ifBlank { obj.optCleanString("rating") }
        )
        val year = obj.optCleanString("year")
            .ifBlank { extractYearFromTitle(name) }
        val plot = obj.optCleanString("description")

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
            streamHeaders = buildStalkerHeaders(
                source = source,
                token = session.token,
                encodeMacCookie = session.encodeMacCookie,
                profileIndex = session.profileIndex,
                extraCookies = session.sessionCookies,
                endpointOverride = session.endpointUrl
            )
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

        val seasonsRaw = executeSessionRequest(
            session = session,
            params = mapOf(
                "type" to "series",
                "action" to "get_ordered_list",
                "movie_id" to rawSeriesId,
                "p" to "1"
            ),
            source = source
        )
        val seasonsData = extractStalkerItemObjects(seasonsRaw)
        if (seasonsData.isEmpty()) return@withContext emptyList()
        val groups = mutableListOf<SeasonGroup>()

        for ((i, seasonObj) in seasonsData.withIndex()) {
            val seasonId = seasonObj.optCleanString("id")
            val seasonName = seasonObj.optCleanString("name").ifBlank { "Season ${i + 1}" }
            val seasonNum = Regex("\\d+").find(seasonName)?.value?.toIntOrNull() ?: (i + 1)

            val episodesRaw = runCatching {
                executeSessionRequest(
                    session = session,
                    params = mapOf(
                        "type" to "series",
                        "action" to "get_ordered_list",
                        "movie_id" to rawSeriesId,
                        "season_id" to seasonId,
                        "p" to "1"
                    ),
                    source = source
                )
            }.getOrNull()

            val episodesList = mutableListOf<MediaItem>()
            val episodesObjects = episodesRaw?.let { extractStalkerItemObjects(it) }.orEmpty()
            val seriesCmd = seasonObj.optCleanString("cmd").ifBlank { seriesItem.streamUrlOrCmd }
            val headers = buildStalkerHeaders(
                source = source,
                token = session.token,
                encodeMacCookie = session.encodeMacCookie,
                profileIndex = session.profileIndex,
                extraCookies = session.sessionCookies,
                endpointOverride = session.endpointUrl
            )

            if (episodesObjects.isNotEmpty()) {
                for ((e, epObj) in episodesObjects.withIndex()) {
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
                        streamHeaders = headers
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
                            streamHeaders = headers
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
     * Dynamically resolves a playable stream URL via Stalker `create_link`.
     * Handles `js.url`, `js.cmd`, `js.stream_url`, top-level `url`/`cmd`, raw `js` strings,
     * `localhost`/`127.0.0.1` host rewriting, relative portal paths, and command prefixes.
     * Reuses cached token for instant zapping and re-handshakes once if token expired.
     */
    suspend fun resolveStreamLink(
        source: PlaylistSource,
        item: MediaItem,
        forceSessionRefresh: Boolean = false
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val rawCmd = item.streamUrlOrCmd.trim()

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

        var session = ensureSession(source, forceRefresh = forceSessionRefresh)
        var resolvedUrl = executeCreateLink(session, params, source)

        // If cached token expired or was rejected, refresh handshake ONCE and retry create_link
        if (resolvedUrl.isBlank() && !forceSessionRefresh) {
            sessionCache.remove(buildSessionCacheKey(source))
            session = ensureSession(source, forceRefresh = true)
            resolvedUrl = executeCreateLink(session, params, source)
        }

        // Some hybrid portals serve series under `type=series` rather than `type=vod`
        if (resolvedUrl.isBlank() && item.type == ContentType.SERIES) {
            resolvedUrl = executeCreateLink(session, params + ("type" to "series"), source)
        }

        val directCleaned = normalizePlayableStreamUrl(session.endpointUrl.ifBlank { source.portalUrl }, cleanCmdForApi)
        val finalUrl = resolvedUrl.ifBlank { directCleaned }
        if (finalUrl.isBlank()) {
            error("Unable to resolve playable URL from Stalker create_link")
        }

        ResolvedStream(
            url = finalUrl,
            headers = buildStalkerHeaders(
                source = source,
                token = session.token,
                encodeMacCookie = session.encodeMacCookie,
                profileIndex = session.profileIndex,
                extraCookies = session.sessionCookies,
                endpointOverride = session.endpointUrl
            )
        )
    }

    private fun executeCreateLink(
        session: StalkerSession,
        params: Map<String, String>,
        source: PlaylistSource
    ): String {
        val responseRaw = runCatching {
            executeSessionRequest(
                session = session,
                params = params,
                source = source,
                readTimeoutSec = FAST_READ_TIMEOUT_SEC
            )
        }.getOrNull() ?: return ""

        val json = parseJsonSafely(responseRaw) ?: return ""
        val jsObj = json.optJSONObject("js")
        val rawCandidate = when {
            jsObj != null -> {
                jsObj.optCleanString("url")
                    .ifBlank { jsObj.optCleanString("cmd") }
                    .ifBlank { jsObj.optCleanString("stream_url") }
            }
            else -> {
                json.optCleanString("js")
                    .ifBlank { json.optCleanString("url") }
                    .ifBlank { json.optCleanString("cmd") }
            }
        }
        return normalizePlayableStreamUrl(session.endpointUrl.ifBlank { source.portalUrl }, rawCandidate)
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
            val raw = executeSessionRequest(
                session = session,
                params = mapOf(
                    "type" to "itv",
                    "action" to "get_short_epg",
                    "ch_id" to channelEpgId,
                    "size" to "10"
                ),
                source = source,
                readTimeoutSec = FAST_READ_TIMEOUT_SEC
            )
            val items = extractStalkerItemObjects(raw)
            val programs = mutableListOf<EpgProgram>()
            for (obj in items) {
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

    /**
     * Normalizes A-to-Z IPTV stream/media URLs (.m3u8, .mpd, .ts, .mp4, .mkv, .avi, .mov, .mp3, .aac, raw/proxy URLs):
     * strips `ffmpeg `, `ffrt* `, `ext `, `auto `, `solution `, `vlc `, quotes/escapes, rewrites `localhost`/`127.0.0.1`
     * to the actual portal host, and resolves relative paths cleanly against the portal base URL.
     */
    fun normalizePlayableStreamUrl(portalUrl: String, rawCmd: String): String {
        val stripped = stripStreamCommandPrefix(rawCmd)
        if (stripped.isBlank()) return ""
        if (stripped.startsWith("http://", ignoreCase = true) ||
            stripped.startsWith("https://", ignoreCase = true)
        ) {
            val parsedStream = stripped.toHttpUrlOrNull()
            val parsedPortal = normalizePortalBase(portalUrl).toHttpUrlOrNull()
            if (parsedStream != null && parsedPortal != null &&
                (parsedStream.host.equals("localhost", ignoreCase = true) || parsedStream.host == "127.0.0.1")
            ) {
                return parsedStream.newBuilder()
                    .host(parsedPortal.host)
                    .build()
                    .toString()
            }
            return stripped
        }
        if (stripped.startsWith("rtsp://", ignoreCase = true) ||
            stripped.startsWith("rtmp://", ignoreCase = true)
        ) {
            return stripped
        }
        return resolvePortalAssetUrl(portalUrl, stripped)
    }

    fun stripStreamCommandPrefix(cmd: String): String {
        val cleaned = cmd
            .replace("\\/", "/")
            .trim()
            .trim('"', '\'')
            .trim()
        if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true) || cleaned.equals("false", ignoreCase = true)) {
            return ""
        }
        val prefixes = listOf("ffmpeg ", "ffrt4 ", "ffrt3 ", "ffrt2 ", "ffrt ", "ext ", "auto ", "solution ", "vlc ")
        var result = cleaned
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (result.startsWith(prefix, ignoreCase = true)) {
                    result = result.substring(prefix.length).trim().trim('"', '\'').trim()
                    changed = true
                }
            }
        }
        val urlMatch = Regex("(https?|rtsp|rtmp)://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(result)
        return (urlMatch?.value ?: result).trim().trim('"', '\'')
    }

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
                is JSONArray -> raw.optString(0).orEmpty()
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