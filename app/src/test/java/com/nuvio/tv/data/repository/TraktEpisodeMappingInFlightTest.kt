package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonDto
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktEpisodeMappingInFlightTest {

    @Test
    fun `cancelled trakt episodes fetch does not block later lookups of the same show`() = runTest {
        val traktApi = mockk<TraktApi>()
        val authService = mockk<TraktAuthService>()
        val firstCallStarted = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { authService.executeAuthorizedRequest<Any>(any()) } coAnswers {
            calls += 1
            if (calls == 1) {
                firstCallStarted.complete(Unit)
                awaitCancellation()
            }
            @Suppress("UNCHECKED_CAST")
            Response.success(
                listOf(
                    TraktSeasonDto(
                        number = 1,
                        episodes = listOf(TraktEpisodeDto(title = "Pilot", season = 1, number = 1))
                    )
                )
            ) as Response<Any>
        }
        val service = TraktEpisodeMappingService(traktApi, authService, mockk<MetaRepository>())

        // A refresh superseded mid-fetch (collectLatest) cancels the first lookup.
        val first = async { service.getTraktEpisodes("tt0248654") }
        firstCallStarted.await()
        first.cancel()

        // Before the fix the orphaned in-flight entry made this await forever.
        val episodes = withTimeout(5_000) { service.getTraktEpisodes("tt0248654") }

        assertEquals(listOf(EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot")), episodes)
    }

    @Test
    fun `remote snapshot is fetched until one has loaded`() {
        assertTrue(shouldFetchRemoteSnapshot(force = false, activityRefreshPending = false, hasLoadedRemoteProgress = false))
    }

    @Test
    fun `activity change seen by a cancelled refresh is still fetched`() {
        assertTrue(shouldFetchRemoteSnapshot(force = false, activityRefreshPending = true, hasLoadedRemoteProgress = true))
    }

    @Test
    fun `unchanged activity after a loaded snapshot skips the fetch`() {
        assertEquals(
            false,
            shouldFetchRemoteSnapshot(force = false, activityRefreshPending = false, hasLoadedRemoteProgress = true)
        )
    }

    @Test
    fun `forced refresh always fetches`() {
        assertTrue(shouldFetchRemoteSnapshot(force = true, activityRefreshPending = false, hasLoadedRemoteProgress = true))
    }
}
