package com.nuvio.tv.updater

/**
 * Pure rules for the "new upstream Nuvio release" notice. Locflix does not update itself
 * from Nuvio's releases; it only tells the user once per new stable Nuvio version so they
 * can look at the release notes.
 */
internal object UpstreamReleasePolicy {
    /** Locflix is forked from Nuvio 1.0.0, so only releases after that are news. */
    const val BASELINE_TAG = "1.0.0"

    const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    /** True when no check has run yet, or the last one was at least a day ago (or in the future, after a clock change). */
    fun isCheckDue(nowMs: Long, lastCheckAtMs: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean {
        if (lastCheckAtMs <= 0L) return true
        if (nowMs < lastCheckAtMs) return true
        return nowMs - lastCheckAtMs >= intervalMs
    }

    /**
     * True when [latestTag] is a parseable, stable version newer than both the baseline and
     * the newest tag the user was already told about.
     */
    fun shouldNotify(
        latestTag: String?,
        lastNotifiedTag: String?,
        baselineTag: String = BASELINE_TAG
    ): Boolean {
        val latest = VersionUtils.parse(latestTag) ?: return false
        if (latest.prerelease.isNotEmpty()) return false
        val baseline = VersionUtils.parse(baselineTag) ?: return false
        if (latest <= baseline) return false
        val notified = VersionUtils.parse(lastNotifiedTag) ?: return true
        return latest > notified
    }

    fun releaseUrl(owner: String, repo: String, tag: String): String =
        "https://github.com/$owner/$repo/releases/tag/$tag"
}
