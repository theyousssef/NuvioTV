package com.nuvio.tv.core.player

import com.nuvio.tv.core.localmedia.LocalMediaStreams
import java.util.Locale

/** Short texts that tell the user where a Continue Watching item resumes and from which source. */
object ResumeInfoFormatter {

    private const val MAX_SOURCE_LABEL_LENGTH = 80
    private const val SEPARATOR = " · "
    private const val LOCAL_PREFIX = "Local"

    private val LOCAL_SIZE_LINE = Regex("""^\d+(\.\d+)?\s*(GB|MB)$""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    /** "23:14 / 58:02", or null when either value is unknown. */
    fun resumeTime(positionMs: Long, durationMs: Long): String? {
        if (positionMs <= 0L || durationMs <= 0L) return null
        return "${clock(positionMs.coerceAtMost(durationMs))} / ${clock(durationMs)}"
    }

    /** m:ss below an hour, h:mm:ss from an hour up. */
    fun clock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * One line naming the source that was playing: "Local · USB Drive" for local files,
     * "Torrentio · 1080p" for addon streams. Null when nothing useful is known.
     */
    fun sourceLabel(addonName: String?, streamName: String?, streamDescription: String?): String? {
        val addon = addonName?.collapseWhitespace()?.takeIf { it.isNotEmpty() }
        val label = if (addon == LocalMediaStreams.ADDON_NAME) {
            localLabel(streamDescription)
        } else {
            val detail = streamDetail(streamName, addon)
            when {
                addon != null && detail != null -> addon + SEPARATOR + detail
                else -> addon ?: detail
            }
        }
        return label?.take(MAX_SOURCE_LABEL_LENGTH)
    }

    // Local stream descriptions start with "<volume> › <folder>", or with the file size when the location is unknown.
    private fun localLabel(description: String?): String {
        val volume = description
            ?.lineSequence()
            ?.firstOrNull()
            ?.substringBefore(" › ")
            ?.collapseWhitespace()
            ?.takeIf { it.isNotEmpty() && !LOCAL_SIZE_LINE.matches(it) }
        return if (volume != null) LOCAL_PREFIX + SEPARATOR + volume else LOCAL_PREFIX
    }

    // Addons usually repeat their own name in the stream name ("Torrentio\n1080p"), so drop it.
    private fun streamDetail(streamName: String?, addon: String?): String? {
        val parts = streamName
            ?.lines()
            ?.map { it.collapseWhitespace() }
            ?.map { line ->
                if (addon != null && line.startsWithWord(addon)) {
                    line.substring(addon.length).trimStart(' ', '-', '|', ':', '·').trim()
                } else {
                    line
                }
            }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun String.startsWithWord(word: String): Boolean =
        startsWith(word, ignoreCase = true) && (length == word.length || !this[word.length].isLetterOrDigit())

    private fun String.collapseWhitespace(): String = trim().replace(WHITESPACE, " ")
}
