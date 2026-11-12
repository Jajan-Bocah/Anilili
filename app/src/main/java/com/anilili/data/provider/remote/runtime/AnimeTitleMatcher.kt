package com.anilili.data.provider.remote.runtime

import kotlin.math.max

object AnimeTitleMatcher {

    data class TitleMetadata(
        val baseTitle: String,
        val season: Int?,
        val part: Int?,
        val isMovie: Boolean,
    )

    private val SEASON_REGEX = Regex("(?i)(?:season|s)\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+season")
    private val PART_REGEX = Regex("(?i)(?:part|cour)\\s*(\\d+)")
    private val NOISE_REGEX = Regex("(?i)\\[[^\\]]*\\]|\\([^\\)]*\\)|subtitle\\s+indonesia|sub\\s+indo|uncensored|bd|batch")

    fun extractMetadata(title: String): TitleMetadata {
        val seasonMatch = SEASON_REGEX.find(title)
        val season = seasonMatch?.let { match ->
            match.groupValues[1].toIntOrNull() ?: match.groupValues[2].toIntOrNull()
        }

        val partMatch = PART_REGEX.find(title)
        val part = partMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val isMovie = title.contains("movie", ignoreCase = true) || title.contains("gekijouban", ignoreCase = true)

        val clean = title
            .replace(SEASON_REGEX, " ")
            .replace(PART_REGEX, " ")
            .replace(NOISE_REGEX, " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

        return TitleMetadata(
            baseTitle = clean,
            season = season,
            part = part,
            isMovie = isMovie,
        )
    }

    /**
     * Calculates match confidence between AniList title (and synonyms) and a search result candidate.
     * Returns a score between 0.0 and 1.0. Returns 0.0 if season numbers conflict.
     */
    fun calculateMatchScore(aniListTitle: String, candidateTitle: String): Double {
        val metaTarget = extractMetadata(aniListTitle)
        val metaCandidate = extractMetadata(candidateTitle)

        // Strict season conflict check: if target has Season 5 and candidate has Season 1, reject immediately!
        if (metaTarget.season != null && metaCandidate.season != null && metaTarget.season != metaCandidate.season) {
            return 0.0
        }

        // Part conflict check
        if (metaTarget.part != null && metaCandidate.part != null && metaTarget.part != metaCandidate.part) {
            return 0.0
        }

        // Format conflict check (Movie vs Non-Movie)
        if (metaTarget.isMovie != metaCandidate.isMovie && (metaTarget.isMovie || candidateTitle.contains("movie", ignoreCase = true))) {
            return 0.0
        }

        val tokens1 = metaTarget.baseTitle.split(" ").filter { it.isNotBlank() }.toSet()
        val tokens2 = metaCandidate.baseTitle.split(" ").filter { it.isNotBlank() }.toSet()

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        // Jaccard similarity on tokens
        val intersection = tokens1.intersect(tokens2).size.toDouble()
        val union = tokens1.union(tokens2).size.toDouble()
        val jaccard = if (union > 0) intersection / union else 0.0

        // Normalized Levenshtein similarity on full base title
        val lev = levenshteinDistance(metaTarget.baseTitle, metaCandidate.baseTitle)
        val maxLen = max(metaTarget.baseTitle.length, metaCandidate.baseTitle.length).toDouble()
        val levSim = if (maxLen > 0) 1.0 - (lev / maxLen) else 0.0

        // Weighted score: 60% Token overlap, 40% String edit distance
        var score = (0.6 * jaccard) + (0.4 * levSim)

        // Bonus if season explicitly matched
        if (metaTarget.season != null && metaTarget.season == metaCandidate.season) {
            score = (score + 0.1).coerceAtMost(1.0)
        }

        return score
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
