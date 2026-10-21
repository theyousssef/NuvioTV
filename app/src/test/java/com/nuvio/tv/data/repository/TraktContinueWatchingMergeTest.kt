package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktContinueWatchingMergeTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    // ---- merge ----

    @Test
    fun `local only items stay in the list`() {
        val remoteMovie = remote("tt1", lastWatched = now - 10 * minute, percent = 40f)
        val localMovie = local("tt2", lastWatched = now - 5 * minute, position = 10, duration = 100)

        val result = mergeTraktAndLocalProgress(listOf(remoteMovie), listOf(localMovie), emptyMap())

        assertEquals(listOf(localMovie, remoteMovie), result)
    }

    @Test
    fun `newer local copy wins and keeps local only metadata`() {
        val remoteCopy = remote("tt1", lastWatched = now - 60 * minute, percent = 20f, playbackId = 7L)
        val localCopy = local(
            "tt1", lastWatched = now - 5 * minute, position = 60, duration = 100, sourceLabel = "Torrentio 1080p"
        )

        val result = mergeTraktAndLocalProgress(listOf(remoteCopy), listOf(localCopy), emptyMap()).single()

        assertEquals(60L, result.position)
        assertEquals(100L, result.duration)
        assertEquals("Torrentio 1080p", result.sourceLabel)
        assertEquals(7L, result.traktPlaybackId)
        assertEquals(localCopy.lastWatched, result.lastWatched)
    }

    @Test
    fun `newer trakt copy wins but borrows the runtime and artwork`() {
        val remoteCopy = remote("tt1", lastWatched = now - 5 * minute, percent = 75f)
        val localCopy = local(
            "tt1", lastWatched = now - 60 * minute, position = 20, duration = 1_000, sourceLabel = "Old source"
        ).copy(poster = "poster")

        val result = mergeTraktAndLocalProgress(listOf(remoteCopy), listOf(localCopy), emptyMap()).single()

        assertEquals(75f, result.progressPercent)
        assertEquals(1_000L, result.duration)
        assertEquals(750L, result.position)
        assertEquals("poster", result.poster)
        assertNull(result.sourceLabel)
        assertEquals(remoteCopy.lastWatched, result.lastWatched)
    }

    @Test
    fun `same progress on both sides keeps the local copy with the newest timestamp`() {
        val localCopy = local("tt1", lastWatched = now - 5 * minute, position = 400, duration = 1_000, sourceLabel = "S")
        val remoteCopy = remote("tt1", lastWatched = now - 4 * minute, percent = 40.5f)

        val result = mergeTraktAndLocalProgress(listOf(remoteCopy), listOf(localCopy), emptyMap()).single()

        assertEquals(400L, result.position)
        assertEquals("S", result.sourceLabel)
        assertEquals(remoteCopy.lastWatched, result.lastWatched)
        assertEquals(WatchProgress.SOURCE_TRAKT_PLAYBACK, result.source)
    }

    @Test
    fun `episodes are deduplicated per show season and episode`() {
        val remoteE1 = remote("tt9", 1, 1, lastWatched = now - 30 * minute, percent = 50f)
        val localE1 = local("TT9", 1, 1, lastWatched = now - 10 * minute, position = 70, duration = 100)
        val localE2 = local("tt9", 1, 2, lastWatched = now - 2 * minute, position = 10, duration = 100)

        val result = mergeTraktAndLocalProgress(listOf(remoteE1), listOf(localE1, localE2), emptyMap())

        assertEquals(2, result.size)
        assertEquals(localE2, result[0])
        assertEquals(70L, result[1].position)
    }

    @Test
    fun `newer local completion hides an older trakt in progress copy`() {
        val remoteCopy = remote("tt1", lastWatched = now - 60 * minute, percent = 40f)
        val localCopy = local("tt1", lastWatched = now - 5 * minute, position = 95, duration = 100)

        val result = mergeTraktAndLocalProgress(listOf(remoteCopy), listOf(localCopy), emptyMap()).single()

        assertTrue(result.isCompleted())
    }

    @Test
    fun `local copy of an item removed on trakt is not resurrected`() {
        val localCopy = local("tt1", lastWatched = now - 5 * minute, position = 30, duration = 100)
        val key = continueWatchingItemKey(localCopy)

        val removed = mergeTraktAndLocalProgress(emptyList(), listOf(localCopy), mapOf(key to localCopy.lastWatched))
        val replayedLocally = mergeTraktAndLocalProgress(
            emptyList(),
            listOf(localCopy),
            mapOf(key to localCopy.lastWatched - minute)
        )

        assertTrue(removed.isEmpty())
        assertEquals(listOf(localCopy), replayedLocally)
    }

    @Test
    fun `shows hidden from progress on trakt stay hidden`() {
        val localCopy = local("tt1", 1, 1, lastWatched = now, position = 30, duration = 100)

        val result = mergeTraktAndLocalProgress(emptyList(), listOf(localCopy), emptyMap()) { it == "tt1" }

        assertTrue(result.isEmpty())
    }

    // ---- push decision ----

    @Test
    fun `pushes local progress trakt does not have`() {
        assertTrue(shouldPush(local("tt1", lastWatched = now - 10 * minute, position = 30, duration = 100)))
    }

    @Test
    fun `pushes when trakt holds an older different version`() {
        val localCopy = local("tt1", lastWatched = now - 10 * minute, position = 60, duration = 100)
        val remoteCopy = remote("tt1", lastWatched = now - 60 * minute, percent = 20f)

        assertTrue(shouldPush(localCopy, remoteCopy))
    }

    @Test
    fun `does not push when trakt is newer or already has the same progress`() {
        val localCopy = local("tt1", lastWatched = now - 10 * minute, position = 60, duration = 100)

        assertFalse(shouldPush(localCopy, remote("tt1", lastWatched = now - 5 * minute, percent = 10f)))
        assertFalse(shouldPush(localCopy, remote("tt1", lastWatched = now - 60 * minute, percent = 60.4f)))
    }

    @Test
    fun `does not push below one percent or from the watched threshold`() {
        assertFalse(shouldPush(local("tt1", lastWatched = now - 10 * minute, position = 5, duration = 1_000)))
        assertFalse(shouldPush(local("tt1", lastWatched = now - 10 * minute, position = 85, duration = 100)))
        assertFalse(shouldPush(local("tt1", lastWatched = now - 10 * minute, position = 95, duration = 100)))
    }

    @Test
    fun `pushes each local version once`() {
        val localCopy = local("tt1", lastWatched = now - 10 * minute, position = 30, duration = 100)

        assertFalse(shouldPush(localCopy, lastPushed = localCopy.lastWatched))
        assertTrue(shouldPush(localCopy, lastPushed = localCopy.lastWatched - minute))
    }

    @Test
    fun `does not push a version trakt had and then dropped`() {
        val localCopy = local("tt1", lastWatched = now - 10 * minute, position = 30, duration = 100)

        assertFalse(shouldPush(localCopy, confirmed = localCopy.lastWatched))
    }

    @Test
    fun `waits for playback to settle and skips old or non trakt items`() {
        assertFalse(shouldPush(local("tt1", lastWatched = now - 30_000L, position = 30, duration = 100)))
        assertFalse(
            shouldPush(
                local("tt1", lastWatched = now - 10 * minute, position = 30, duration = 100),
                oldestAllowed = now - 5 * minute
            )
        )
        assertFalse(shouldPush(local("kitsu:12", lastWatched = now - 10 * minute, position = 30, duration = 100)))
    }

    private fun shouldPush(
        local: WatchProgress,
        remote: WatchProgress? = null,
        lastPushed: Long? = null,
        confirmed: Long? = null,
        oldestAllowed: Long? = null
    ) = shouldPushLocalProgressToTrakt(
        local = local,
        remote = remote,
        lastPushedVersion = lastPushed,
        confirmedVersion = confirmed,
        nowMs = now,
        oldestAllowedMs = oldestAllowed
    )

    private fun local(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long,
        position: Long,
        duration: Long,
        sourceLabel: String? = null
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = "Title $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = if (season == null) contentId else "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        sourceLabel = sourceLabel
    )

    private fun remote(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long,
        percent: Float,
        playbackId: Long? = null
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = "Title $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 0L,
        duration = 0L,
        lastWatched = lastWatched,
        progressPercent = percent,
        source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
        traktPlaybackId = playbackId
    )
}
