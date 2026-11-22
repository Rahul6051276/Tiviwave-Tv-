package com.example.services

import com.example.models.ContentType

object CountryCategoryClassifier {

    private data class RegionRule(
        val groupName: String,
        val prefixTokens: Set<String>,
        val keywords: List<String>
    )

    private val rules = listOf(
        RegionRule(
            groupName = "India",
            prefixTokens = setOf("IN", "IND", "INDIA", "HIN", "HINDI", "DESI"),
            keywords = listOf(
                "INDIA", "HINDI", "BOLLYWOOD", "TAMIL", "TELUGU", "PUNJABI",
                "BENGALI", "MARATHI", "MALAYALAM", "KANNADA", "GUJARATI",
                "BHOJPURI", "ODIA", "ASSAMESE", "DESI", "TATA", "ZEE", "STAR PLUS",
                "COLORS", "SONY SAB", "SUN TV", "AAJ TAK"
            )
        ),
        RegionRule(
            groupName = "United Kingdom",
            prefixTokens = setOf("UK", "GB", "GBR", "EN-UK"),
            keywords = listOf(
                "UNITED KINGDOM", "BRITAIN", "BRITISH", "SKY UK", "BBC", "ITV ",
                "CHANNEL 4", "BT SPORT", "TNT SPORTS UK", "EPL UK", "UK |"
            )
        ),
        RegionRule(
            groupName = "United States",
            prefixTokens = setOf("US", "USA", "EN-US"),
            keywords = listOf(
                "UNITED STATES", "USA", "AMERICAN", "US |", "US:", "HBO",
                "SHOWTIME", "NBC", "CBS", "ABC ", "FOX ", "ESPN US", "CNN US",
                "NFL", "MLB", "NBA"
            )
        ),
        RegionRule(
            groupName = "UAE & Middle East",
            prefixTokens = setOf("AE", "UAE", "AR", "ARB", "ARAB", "KSA", "SA", "QA", "MENA"),
            keywords = listOf(
                "UAE", "DUBAI", "EMIRATES", "ARABIC", "ARAB", "MIDDLE EAST",
                "MENA", "OSN", "MBC", "BEIN", "SHAHID", "SAUDI", "QATAR", "EGYPT"
            )
        ),
        RegionRule(
            groupName = "Pakistan",
            prefixTokens = setOf("PK", "PAK", "UR", "URDU"),
            keywords = listOf(
                "PAKISTAN", "URDU", "GEO TV", "ARY ", "HUM TV", "PTV", "SAMA TV", "DUNYA"
            )
        ),
        RegionRule(
            groupName = "Canada",
            prefixTokens = setOf("CA", "CAN", "CANADA"),
            keywords = listOf(
                "CANADA", "CANADIAN", "TSN", "SPORTSNET", "CBC ", "CTV ", "QUEBEC"
            )
        ),
        RegionRule(
            groupName = "Sports",
            prefixTokens = setOf("SP", "SPT", "SPORT", "SPORTS", "PPV"),
            keywords = listOf(
                "SPORTS", "SPORT", "CRICKET", "FOOTBALL", "SOCCER", "UFC", "MMA",
                "BOXING", "FORMULA 1", "F1 ", "MOTORSPORT", "TENNIS", "GOLF",
                "DAZN", "PPV", "LIVE EVENTS", "BEIN SPORTS", "SKY SPORTS", "WILLOW"
            )
        ),
        RegionRule(
            groupName = "Movies & Cinema",
            prefixTokens = setOf("MOV", "MOVIE", "MOVIES", "VOD", "CINEMA", "4K", "UHD"),
            keywords = listOf(
                "MOVIES", "MOVIE", "CINEMA", "BOX OFFICE", "4K UHD", "BLOCKBUSTER",
                "ACTION", "THRILLER", "COMEDY", "HORROR", "SCI-FI", "DRAMA",
                "DOCUMENTARY", "ANIMATION", "ANIME", "WEB SERIES", "NETFLIX",
                "PRIME VIDEO", "DISNEY+", "APPLE TV", "HOTSTAR"
            )
        )
    )

    val orderedGroups = listOf(
        "India",
        "United Kingdom",
        "United States",
        "UAE & Middle East",
        "Pakistan",
        "Canada",
        "Sports",
        "Movies & Cinema",
        "International"
    )

    fun classifyCountryGroup(categoryName: String): String {
        val raw = categoryName.trim()
        if (raw.isEmpty()) return "International"
        val upper = raw.uppercase()

        // Extract leading country prefix token like "IN|", "IND:", "[UK]", "USA -", "AE/"
        val prefixMatch = Regex("^[^A-Z0-9]*([A-Z]{2,6})(?:\\s*[|:\\-/\\]\\[]|\\s+)").find(upper)
        val leadingToken = prefixMatch?.groupValues?.getOrNull(1)

        if (leadingToken != null) {
            rules.firstOrNull { leadingToken in it.prefixTokens }?.let { return it.groupName }
        }

        // Check full-word tokens and keywords across the category title
        val wordTokens = upper.split(Regex("[^A-Z0-9+]+")).filter { it.isNotEmpty() }.toSet()
        for (rule in rules) {
            if (rule.prefixTokens.any { it.length >= 2 && it in wordTokens }) {
                return rule.groupName
            }
        }
        for (rule in rules) {
            if (rule.keywords.any { upper.contains(it) }) {
                return rule.groupName
            }
        }

        return "International"
    }

    fun inferContentType(groupTitle: String, title: String, streamUrl: String): ContentType {
        val combined = "${groupTitle.uppercase()} ${title.uppercase()}"
        val lowerUrl = streamUrl.lowercase()

        if (
            lowerUrl.contains("/series/") ||
            Regex("S\\d{1,2}\\s*E\\d{1,2}").containsMatchIn(combined) ||
            combined.contains("WEB SERIES") ||
            combined.contains("SERIES") ||
            combined.contains("SEASON ") ||
            combined.contains("EPISODE ")
        ) {
            return ContentType.SERIES
        }

        if (
            lowerUrl.contains("/movie/") ||
            lowerUrl.endsWith(".mp4") ||
            lowerUrl.endsWith(".mkv") ||
            lowerUrl.endsWith(".avi") ||
            lowerUrl.endsWith(".mov") ||
            combined.contains("VOD") ||
            combined.contains("MOVIES") ||
            combined.contains("BOLLYWOOD") ||
            combined.contains("HOLLYWOOD") ||
            combined.contains("CINEMA")
        ) {
            return ContentType.VOD
        }

        return ContentType.LIVE
    }
}
