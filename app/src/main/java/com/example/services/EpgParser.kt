package com.example.services

import android.util.Xml
import com.example.models.EpgProgram
import com.example.routing.RoutingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class EpgParser {

    /**
     * Streams and parses an XMLTV (.xml or .xml.gz) feed over the active RoutingManager OkHttpClient.
     * Filters programmes to [channelIdsFilter] (if non-empty) and a 48-hour relevance window to stay
     * lean on Android TV memory.
     */
    suspend fun fetchAndParseXmltv(
        epgUrl: String,
        channelIdsFilter: Set<String> = emptySet()
    ): Map<String, List<EpgProgram>> = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) return@withContext emptyMap()

        val client = RoutingManager.buildHttpClient(readTimeoutSec = 45L)
        val request = Request.Builder()
            .url(epgUrl.trim())
            .header("User-Agent", "TiviWaveTV/1.0 (AndroidTV)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("EPG download failed with HTTP ${response.code}")
            }
            val bodyStream = response.body?.byteStream() ?: return@withContext emptyMap()
            val buffered = BufferedInputStream(bodyStream)
            buffered.mark(4)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()

            val isGzip = (b0 == 0x1f && b1 == 0x8b) || epgUrl.endsWith(".gz", ignoreCase = true)
            val xmlStream: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered
            xmlStream.use { parseStream(it, channelIdsFilter) }
        }
    }

    fun parseStream(
        inputStream: InputStream,
        channelIdsFilter: Set<String> = emptySet()
    ): Map<String, List<EpgProgram>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }

        val normalizedFilter = channelIdsFilter.map { it.lowercase() }.toSet()
        val nowMs = System.currentTimeMillis()
        val minRelevantMs = nowMs - 4 * 3600_000L
        val maxRelevantMs = nowMs + 36 * 3600_000L

        val result = HashMap<String, MutableList<EpgProgram>>()
        var currentChannelId: String? = null
        var currentStartMs = 0L
        var currentEndMs = 0L
        var currentTitle = ""
        var currentDesc = ""
        var inProgramme = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "programme" -> {
                            val chId = parser.getAttributeValue(null, "channel").orEmpty().trim()
                            val acceptChannel = normalizedFilter.isEmpty() || chId.lowercase() in normalizedFilter
                            if (acceptChannel && chId.isNotEmpty()) {
                                val start = parseXmltvTimestamp(parser.getAttributeValue(null, "start"))
                                val stop = parseXmltvTimestamp(parser.getAttributeValue(null, "stop"))
                                if (stop >= minRelevantMs && start <= maxRelevantMs) {
                                    inProgramme = true
                                    currentChannelId = chId
                                    currentStartMs = start
                                    currentEndMs = if (stop > start) stop else start + 1800_000L
                                    currentTitle = ""
                                    currentDesc = ""
                                }
                            }
                        }
                        "title" -> if (inProgramme && currentTitle.isEmpty()) {
                            currentTitle = runCatching { parser.nextText().trim() }.getOrDefault("")
                        }
                        "desc" -> if (inProgramme && currentDesc.isEmpty()) {
                            currentDesc = runCatching { parser.nextText().trim() }.getOrDefault("")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("programme", ignoreCase = true) && inProgramme) {
                        val chId = currentChannelId
                        if (!chId.isNullOrBlank() && currentTitle.isNotBlank()) {
                            val list = result.getOrPut(chId) { mutableListOf() }
                            list += EpgProgram(
                                channelId = chId,
                                title = currentTitle,
                                description = currentDesc,
                                startTimeMs = currentStartMs,
                                endTimeMs = currentEndMs
                            )
                        }
                        inProgramme = false
                        currentChannelId = null
                    }
                }
            }
            eventType = parser.next()
        }

        return result.mapValues { (_, list) -> list.sortedBy { it.startTimeMs } }
    }

    private fun parseXmltvTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmssZ",
            "yyyyMMddHHmmss"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (!pattern.contains("Z")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(cleaned)
                if (parsed != null) return parsed.time
            }
        }
        return 0L
    }
}
