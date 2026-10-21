package com.nuvio.tv.core.localmedia

import kotlin.math.abs
import kotlin.math.min

/** A video found on the device or on an attached USB drive. */
data class LocalVideoFile(
    val uri: String,
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val volumeLabel: String? = null
)

/** What the user is about to play. [titles] holds the display title first, then any aliases. */
data class LocalMediaQuery(
    val titles: List<String>,
    val year: Int?,
    val season: Int?,
    val episode: Int?
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

data class LocalMediaMatch(
    val file: LocalVideoFile,
    val parsed: ParsedLocalName,
    val score: Double
)

object LocalMediaMatcher {

    const val MIN_SCORE = 0.6

    private val STOPWORDS = setOf("the", "a", "an", "of", "and", "in", "on", "to")

    /** Every acceptable match, best first. An episode query only returns that exact episode. */
    fun rank(query: LocalMediaQuery, files: List<LocalVideoFile>): List<LocalMediaMatch> {
        if (query.titles.none { it.isNotBlank() }) return emptyList()
        return files.asSequence()
            .distinctBy { it.uri }
            .mapNotNull { file -> score(query, file) }
            .sortedWith(
                compareByDescending<LocalMediaMatch> { it.score }
                    .thenByDescending { it.parsed.resolution ?: 0 }
                    .thenByDescending { it.file.sizeBytes ?: 0L }
            )
            .toList()
    }

    private fun score(query: LocalMediaQuery, file: LocalVideoFile): LocalMediaMatch? {
        val parsed = LocalMediaNameParser.parse(file.relativePath, file.fileName)
        if (parsed.isExtra) return null

        if (query.isEpisode) {
            if (parsed.season != query.season || parsed.episode != query.episode) return null
        } else if (parsed.hasEpisodeMarker) {
            return null
        }

        // Nearer names (the file itself, then its own folder) are slightly more trustworthy.
        var best = 0.0
        parsed.titleCandidates.forEachIndexed { index, candidate ->
            val weight = if (index == 0) 1.0 else 0.97
            query.titles.forEach { title -> best = maxOf(best, titleScore(title, candidate) * weight) }
        }
        if (best == 0.0) return null

        val score = best + yearAdjustment(query, parsed)
        return if (score >= MIN_SCORE) LocalMediaMatch(file, parsed, score) else null
    }

    private fun yearAdjustment(query: LocalMediaQuery, parsed: ParsedLocalName): Double {
        val wanted = query.year ?: return 0.0
        val found = parsed.year ?: return 0.0
        val diff = abs(wanted - found)
        return when {
            diff == 0 -> 0.05
            diff == 1 -> 0.0
            // Shows often carry a later season's year in folder names, so only movies get the strong penalty.
            query.isEpisode -> -0.1
            // A different year usually means a remake ("Dune" 1984 vs 2021), so even an exact title must not pass.
            else -> -0.5
        }
    }

    /** 0..1 similarity between a known title and a title read from a file or folder name. */
    fun titleScore(title: String, candidate: String): Double {
        val q = LocalMediaNameParser.normalize(title).removePrefix("the ")
        val c = LocalMediaNameParser.normalize(candidate).removePrefix("the ")
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        if (q.replace(" ", "") == c.replace(" ", "")) return 0.98

        val qTokens = q.split(' ')
        val cTokens = c.split(' ')

        // "GOT" for "Game of Thrones", "HIMYM" for "How I Met Your Mother".
        if (cTokens.size == 1 && cTokens[0].length >= 2 && qTokens.size >= 2) {
            val initials = qTokens.joinToString("") { it.take(1) }
            val contentInitials = qTokens.filterNot { it in STOPWORDS }.joinToString("") { it.take(1) }
            if (cTokens[0] == initials || cTokens[0] == contentInitials) return 0.9
        }

        val recall = qTokens.count { qt -> cTokens.any { tokensMatch(qt, it) } }.toDouble() / qTokens.size
        val precision = cTokens.count { ct -> qTokens.any { tokensMatch(ct, it) } }.toDouble() / cTokens.size
        if (recall == 0.0 || precision == 0.0) return 0.0
        return 0.95 * (2 * recall * precision / (recall + precision))
    }

    private fun tokensMatch(a: String, b: String): Boolean =
        a == b || (a.length >= 5 && b.length >= 5 && levenshtein(a, b) <= 1)

    private fun levenshtein(a: String, b: String): Int {
        if (abs(a.length - b.length) > 1) return 2
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
