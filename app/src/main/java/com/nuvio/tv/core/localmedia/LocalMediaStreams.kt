package com.nuvio.tv.core.localmedia

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import java.util.Locale

/** Presents local matches as a regular source group so the stream list, chips and player need no special casing. */
object LocalMediaStreams {

    const val ADDON_NAME = "Local files"

    /** Matches shown before the user asks for more. */
    const val COLLAPSED_COUNT = 1

    private val YEAR = Regex("""(19|20)\d{2}""")

    fun query(title: String, contentName: String?, year: String?, season: Int?, episode: Int?): LocalMediaQuery =
        LocalMediaQuery(
            titles = listOfNotNull(contentName, title).filter { it.isNotBlank() }.distinct(),
            year = year?.let { YEAR.find(it)?.value?.toIntOrNull() },
            season = season,
            episode = episode
        )

    /**
     * The "Local files" group: the best match, followed by a "show more" card while collapsed.
     * Returns null when nothing matched.
     */
    fun group(matches: List<LocalMediaMatch>, expanded: Boolean, showMoreLabel: (Int) -> String): AddonStreams? {
        if (matches.isEmpty()) return null
        val streams = matches.map { it.toStream() }
        val hidden = streams.size - COLLAPSED_COUNT
        val visible = if (expanded || hidden <= 0) {
            streams
        } else {
            streams.take(COLLAPSED_COUNT) + showMoreStream(showMoreLabel(hidden))
        }
        return AddonStreams(addonName = ADDON_NAME, addonLogo = null, streams = visible)
    }

    fun Stream.isLocalFile(): Boolean = addonName == ADDON_NAME && url != null

    /** The placeholder card that expands the group. It has no URL, so autoplay never picks it. */
    fun Stream.isLocalShowMore(): Boolean = addonName == ADDON_NAME && url == null

    private fun showMoreStream(label: String) = Stream(
        name = label,
        title = null,
        description = null,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = ADDON_NAME,
        addonLogo = null
    )

    private fun LocalMediaMatch.toStream(): Stream {
        val resolution = parsed.resolution
        val location = listOfNotNull(file.volumeLabel, file.relativePath.trim('/').takeIf { it.isNotEmpty() })
            .joinToString(" › ")
        return Stream(
            name = listOfNotNull("Local", resolution?.let { "${it}p" }).joinToString(" "),
            title = file.fileName,
            description = listOfNotNull(
                location.takeIf { it.isNotEmpty() },
                file.sizeBytes?.let(::formatSize)
            ).joinToString("\n").takeIf { it.isNotEmpty() },
            url = file.uri,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoSize = file.sizeBytes,
                filename = file.fileName
            ),
            addonName = ADDON_NAME,
            addonLogo = null,
            quality = resolution?.let { "${it}p" },
            qualityValue = resolution ?: -1
        )
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
