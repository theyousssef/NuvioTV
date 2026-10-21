package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress
import kotlin.math.abs

/*
 * Continue Watching while Trakt is connected = Trakt playback/history merged with the app's own
 * local watch history. Each item (movie, or show + season + episode) is deduplicated and the copy
 * with the most recent update wins. These are pure functions so the rules can be unit-tested.
 */

/** Two copies whose progress differs by at most this many percentage points are the same version. */
internal const val SAME_PROGRESS_TOLERANCE_PERCENT = 1.5f

/** Trakt rejects scrobbles below 1% with HTTP 422. */
internal const val TRAKT_MIN_PUSH_PERCENT = 1f

/**
 * Trakt treats a scrobble at or above 80% as watched, so higher local progress is not pushed as a
 * paused playback (completion is recorded by the player's own scrobble, as upstream does).
 */
internal const val TRAKT_MAX_PUSH_PERCENT = 80f

/** Local progress must be untouched this long before it is pushed (the player scrobbles itself). */
internal const val TRAKT_PUSH_SETTLE_MS = 2 * 60_000L

/** Identity of a progress entry: movie, or show + season + episode. */
internal fun continueWatchingItemKey(progress: WatchProgress): String {
    val contentId = progress.contentId.trim().lowercase()
    val season = progress.season
    val episode = progress.episode
    return if (season != null && episode != null) "${contentId}_s${season}e$episode" else contentId
}

private fun WatchProgress.percent(): Float = progressPercentage * 100f

private fun isSameProgressVersion(a: WatchProgress, b: WatchProgress): Boolean =
    abs(a.percent() - b.percent()) <= SAME_PROGRESS_TOLERANCE_PERCENT

/**
 * Picks the copy of one item that should be shown. The newest copy wins; when both copies hold the
 * same progress (e.g. the app's own scrobble came back from Trakt) the local copy is kept because it
 * carries the exact position, duration and local-only data such as the source label.
 */
internal fun resolveNewestProgressCopy(remote: WatchProgress, local: WatchProgress): WatchProgress {
    val sameVersion = isSameProgressVersion(remote, local)
    return if (local.lastWatched >= remote.lastWatched || sameVersion) {
        local.copy(
            name = local.name.takeUnless { it.isBlank() || it == local.contentId } ?: remote.name,
            poster = local.poster ?: remote.poster,
            backdrop = local.backdrop ?: remote.backdrop,
            logo = local.logo ?: remote.logo,
            episodeTitle = local.episodeTitle ?: remote.episodeTitle,
            lastWatched = maxOf(local.lastWatched, remote.lastWatched),
            source = if (sameVersion) remote.source else local.source,
            traktPlaybackId = remote.traktPlaybackId ?: local.traktPlaybackId,
            traktMovieId = remote.traktMovieId ?: local.traktMovieId,
            traktShowId = remote.traktShowId ?: local.traktShowId,
            traktEpisodeId = remote.traktEpisodeId ?: local.traktEpisodeId
        )
    } else {
        // Trakt is newer (watched elsewhere). Keep its progress; borrow only artwork and the
        // runtime so the resume position can be computed precisely.
        val duration = remote.duration.takeIf { it > 0L } ?: local.duration
        val position = when {
            remote.position > 0L -> remote.position
            duration > 0L && remote.progressPercent != null ->
                (duration * (remote.progressPercent / 100f).coerceIn(0f, 1f)).toLong()
            else -> remote.position
        }
        remote.copy(
            name = remote.name.takeUnless { it.isBlank() || it == remote.contentId } ?: local.name,
            poster = remote.poster ?: local.poster,
            backdrop = remote.backdrop ?: local.backdrop,
            logo = remote.logo ?: local.logo,
            episodeTitle = remote.episodeTitle ?: local.episodeTitle,
            duration = duration,
            position = position
        )
    }
}

/**
 * Merges Trakt's entries with the local watch history.
 *
 * @param confirmedVersions item key -> local `lastWatched` of a local copy that Trakt was seen
 * holding. A local-only entry whose version Trakt already had has since been removed or finished on
 * Trakt (another device, the website), so it is not resurrected. A newer local play is shown again.
 * @param isHiddenOnRemote shows the user hid from progress on Trakt stay hidden.
 */
internal fun mergeTraktAndLocalProgress(
    remoteEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    confirmedVersions: Map<String, Long>,
    isHiddenOnRemote: (String) -> Boolean = { false }
): List<WatchProgress> {
    val merged = linkedMapOf<String, WatchProgress>()
    remoteEntries.forEach { remote ->
        val key = continueWatchingItemKey(remote)
        val existing = merged[key]
        if (existing == null || remote.lastWatched > existing.lastWatched) merged[key] = remote
    }
    val remoteKeys = merged.keys.toSet()
    val newestLocal = linkedMapOf<String, WatchProgress>()
    localEntries.forEach { local ->
        val key = continueWatchingItemKey(local)
        val existing = newestLocal[key]
        if (existing == null || local.lastWatched > existing.lastWatched) newestLocal[key] = local
    }
    newestLocal.forEach { (key, local) ->
        if (key in remoteKeys) {
            merged[key] = resolveNewestProgressCopy(merged.getValue(key), local)
        } else {
            val removedOnRemote = confirmedVersions[key]?.let { it >= local.lastWatched } == true
            if (!removedOnRemote && !isHiddenOnRemote(local.contentId)) merged[key] = local
        }
    }
    return merged.values.sortedByDescending(WatchProgress::lastWatched)
}

/**
 * True when Trakt holds (or already held) this local version, so it can count as confirmed.
 */
internal fun isLocalVersionOnRemote(local: WatchProgress, remote: WatchProgress?): Boolean {
    remote ?: return false
    return remote.lastWatched >= local.lastWatched || isSameProgressVersion(remote, local)
}

/**
 * Decides whether a local progress entry should be written to Trakt (via `scrobble/pause`)
 * because Trakt is missing it or holds an older version.
 *
 * @param lastPushedVersion local `lastWatched` of the last version already pushed for this item
 * (successfully, or rejected by Trakt) — each version is pushed at most once.
 * @param confirmedVersion local `lastWatched` that Trakt was seen holding; if Trakt has since
 * dropped the item it was removed there, and pushing again would undo that.
 * @param oldestAllowedMs entries older than this (the Continue Watching window) are left alone so
 * old items are not bumped to the top of Trakt.
 */
internal fun shouldPushLocalProgressToTrakt(
    local: WatchProgress,
    remote: WatchProgress?,
    lastPushedVersion: Long?,
    confirmedVersion: Long?,
    nowMs: Long,
    oldestAllowedMs: Long?,
    settleMs: Long = TRAKT_PUSH_SETTLE_MS
): Boolean {
    if (!isTraktCompatibleId(resolveEffectiveContentId(local.contentId, local.videoId))) return false
    val percent = local.percent()
    if (percent < TRAKT_MIN_PUSH_PERCENT || percent >= TRAKT_MAX_PUSH_PERCENT) return false
    if (local.isCompleted()) return false
    if (lastPushedVersion != null && lastPushedVersion >= local.lastWatched) return false
    if (confirmedVersion != null && confirmedVersion >= local.lastWatched) return false
    if (local.lastWatched > nowMs) return false
    if (nowMs - local.lastWatched < settleMs) return false
    if (oldestAllowedMs != null && local.lastWatched < oldestAllowedMs) return false
    if (remote != null && isLocalVersionOnRemote(local, remote)) return false
    return true
}
