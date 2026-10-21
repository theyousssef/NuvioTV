package com.nuvio.tv.core.localmedia

import java.text.Normalizer
import java.util.Locale

/**
 * What could be read out of a local video's path: the candidate titles (from the
 * file name and from each parent folder, nearest first), a release year, and a
 * season/episode pair when the file looks like an episode.
 */
data class ParsedLocalName(
    val titleCandidates: List<String>,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val resolution: Int?,
    val isExtra: Boolean
) {
    val hasEpisodeMarker: Boolean get() = episode != null
}

/**
 * Parses messy, hand-named local files such as:
 *  - `GOT - s01e01.mkv`
 *  - `game.of.thrones.s01e01.720p.mkv`
 *  - `Game of Thrones (2011) blabla s01 e01 BluRay.mkv`
 *  - `GOT/S01/1.mkv` (show and season only known from the folders)
 */
object LocalMediaNameParser {

    private val SXXEYY = Regex("""(?<![a-z0-9'’])s(\d{1,2})[\s._-]*e(\d{1,3})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val NXNN = Regex("""(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE_WORDS = Regex(
        """(?:season|saison|staffel|temporada)[\s._-]*(\d{1,2})[\s._-]*(?:episode|ep|e|folge|capitulo)[\s._-]*(\d{1,3})(?![0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val EPISODE_ONLY = Regex(
        """(?<![a-z0-9])(?:episode|ep|e)[\s._-]*(\d{1,3})(?![0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_NUMBER = Regex("""^\s*(\d{1,3})(?![0-9])(?:\s*[-._)\]]|\s|$)""")
    private val SEASON_FOLDER = Regex(
        """^(?:s|season|saison|staffel|temporada|series)[\s._-]*(\d{1,2})(?![0-9])""",
        RegexOption.IGNORE_CASE
    )
    // A season mentioned inside a longer folder name, e.g. "Game.of.Thrones.S01.1080p".
    private val SEASON_IN_TEXT = Regex(
        """(?<![a-z0-9'’])(?:s|season|saison|staffel|temporada)[\s._-]?(\d{1,2})(?![0-9])(?![\s._-]*e\d)""",
        RegexOption.IGNORE_CASE
    )
    private val YEAR = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")
    private val RESOLUTION = Regex("""(?<![0-9])(2160|1080|720|576|480)[pi]""", RegexOption.IGNORE_CASE)
    private val UHD = Regex("""(?<![a-z0-9])(4k|uhd)(?![a-z0-9])""", RegexOption.IGNORE_CASE)
    private val EXTRA = Regex("""(?<![a-z0-9])(sample|trailer|featurette|behind[\s._-]the[\s._-]scenes)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    // Release-group noise: everything from the first of these onward is not part of the title.
    private val RELEASE_TAGS = setOf(
        "2160p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hdr", "hdr10", "dv", "dovi",
        "bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "webrip", "webdl", "web", "hdtv", "hdrip",
        "dvdrip", "dvd", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "aac", "ac3", "dts",
        "ddp", "dd5", "atmos", "truehd", "proper", "repack", "extended", "unrated", "remastered",
        "multi", "dual", "subbed", "dubbed", "complete", "amzn", "nf", "dsnp", "hmax", "atvp"
    )

    // Folder names that say where a file lives, not what it is.
    private val GENERIC_FOLDERS = setOf(
        "movies", "movie", "films", "film", "tv", "tv shows", "tvshows", "shows", "series", "videos",
        "video", "download", "downloads", "media", "usb", "storage", "emulated", "0", "dcim",
        "torrents", "complete", "completed", "new folder"
    )

    fun parse(relativePath: String, fileName: String): ParsedLocalName {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val folders = relativePath.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .reversed()

        var season: Int? = null
        var episode: Int? = null
        var titleFromFile: String? = null

        val explicit = SXXEYY.find(baseName) ?: SEASON_EPISODE_WORDS.find(baseName) ?: NXNN.find(baseName)
        if (explicit != null) {
            season = explicit.groupValues[1].toInt()
            episode = explicit.groupValues[2].toInt()
            titleFromFile = baseName.substring(0, explicit.range.first)
        }

        val seasonFolderIndex = folders.indexOfFirst { SEASON_FOLDER.containsMatchIn(it.normalizeSeparators()) }
        val folderSeason = folders.firstNotNullOfOrNull { folder ->
            SEASON_IN_TEXT.find(folder.normalizeSeparators())?.groupValues?.get(1)?.toInt()
        }

        if (explicit == null && folderSeason != null) {
            val episodeMatch = EPISODE_ONLY.find(baseName)
            val leading = LEADING_NUMBER.find(baseName)
            val number = episodeMatch?.groupValues?.get(1) ?: leading?.groupValues?.get(1)
            if (number != null) {
                season = folderSeason
                episode = number.toInt()
                titleFromFile = episodeMatch?.let { baseName.substring(0, it.range.first) }
            }
        }

        // Anything above a season folder (or above the file when there is none) can name the title.
        val titleFolders = folders
            .filterIndexed { index, _ -> index != seasonFolderIndex }
            .filterNot { it.lowercase(Locale.ROOT) in GENERIC_FOLDERS }

        val rawTitles = buildList {
            if (episode == null) add(baseName) else titleFromFile?.let { add(it) }
            addAll(titleFolders)
        }

        var year: Int? = null
        val titles = rawTitles.mapNotNull { raw ->
            val (title, rawYear) = cleanTitle(raw)
            if (year == null) year = rawYear
            title.takeIf { it.isNotBlank() }
        }.distinct()

        val fullText = "$relativePath/$baseName"
        val resolution = RESOLUTION.find(fullText)?.groupValues?.get(1)?.toInt()
            ?: if (UHD.containsMatchIn(fullText)) 2160 else null

        return ParsedLocalName(
            titleCandidates = titles,
            year = year,
            season = season,
            episode = episode,
            resolution = resolution,
            isExtra = EXTRA.containsMatchIn(baseName)
        )
    }

    /** Returns the readable title part of [raw] and the year found right after it, if any. */
    private fun cleanTitle(raw: String): Pair<String, Int?> {
        val text = raw.normalizeSeparators()
        // The last year wins so "Blade Runner 2049 (2017)" keeps 2049 in the title.
        val yearMatch = YEAR.findAll(text).lastOrNull { it.range.first > 0 }
        var title = if (yearMatch != null) text.substring(0, yearMatch.range.first) else text
        SEASON_IN_TEXT.find(title)?.let { title = title.substring(0, it.range.first) }

        val words = title.split(' ').filter { it.isNotEmpty() }
        val cut = words.indexOfFirst { normalize(it).replace(" ", "") in RELEASE_TAGS }
        val titleWords = if (cut >= 0) words.take(cut) else words
        return titleWords.joinToString(" ").trim(' ', '-', '(', '[') to yearMatch?.value?.toInt()
    }

    private fun String.normalizeSeparators(): String =
        replace(Regex("""[._]+"""), " ")
            .replace(Regex("""[\[\](){}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Lowercase ASCII words separated by single spaces, for comparing titles. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("""['’`]"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}
