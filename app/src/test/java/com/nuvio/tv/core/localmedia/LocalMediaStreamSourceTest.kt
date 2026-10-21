package com.nuvio.tv.core.localmedia

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaStreamSourceTest {

    private val query = LocalMediaQuery(titles = listOf("Fringe"), year = 2008, season = 1, episode = 4)

    private fun match(name: String) = LocalMediaMatch(
        file = LocalVideoFile(uri = "content://media/external/video/media/$name", relativePath = "Movies/", fileName = name),
        parsed = LocalMediaNameParser.parse("Movies/", name),
        score = 1.0
    )

    private val matches = listOf(match("Fringe.S01E04.720p.mkv"), match("Fringe.S01E04.mkv"), match("Fringe S01E04 copy.mkv"))

    private val addonGroup = AddonStreams(
        addonName = "Torrentio",
        addonLogo = null,
        streams = listOf(
            Stream(
                name = "1080p", title = null, description = null, url = "https://example.com/a.mkv",
                ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
                addonName = "Torrentio", addonLogo = null
            )
        )
    )

    private fun merge(
        remote: Flow<NetworkResult<List<AddonStreams>>>,
        found: List<LocalMediaMatch>,
        group: (List<LocalMediaMatch>?) -> AddonStreams? = { it?.let { m -> LocalMediaStreams.group(m) } }
    ) = prependLocalGroup(remote, flowOf(null, found), group) { "local: $it" }

    @Test
    fun `local match replaces the no addon error`() = runTest {
        val result = merge(flowOf(NetworkResult.Error("No installed addon supports streams")), matches).last()

        result as NetworkResult.Success
        assertEquals(listOf(LocalMediaStreams.ADDON_NAME), result.data.map { it.addonName })
        // The player lists every match and never receives the URL-less "show more" card.
        assertEquals(3, result.data.single().streams.size)
        assertTrue(result.data.single().streams.all { it.url != null })
    }

    @Test
    fun `local group goes before addon results`() = runTest {
        val result = merge(flowOf(NetworkResult.Loading, NetworkResult.Success(listOf(addonGroup))), matches).last()

        result as NetworkResult.Success
        assertEquals(listOf(LocalMediaStreams.ADDON_NAME, "Torrentio"), result.data.map { it.addonName })
    }

    @Test
    fun `loading becomes success once a local match is known`() = runTest {
        val results = merge(flowOf(NetworkResult.Loading), matches).toList()

        assertEquals(NetworkResult.Loading, results.first())
        assertTrue(results.last() is NetworkResult.Success)
    }

    @Test
    fun `error mentions local files when nothing matched`() = runTest {
        val results = merge(flowOf(NetworkResult.Error("no addons")), emptyList()).toList()

        // While the device is still being scanned the addon error is held back.
        assertEquals(listOf(NetworkResult.Loading, NetworkResult.Error("local: no addons")), results)
    }

    @Test
    fun `addon results pass through when nothing matched`() = runTest {
        val result = merge(flowOf(NetworkResult.Success(listOf(addonGroup))), emptyList()).last()

        assertEquals(NetworkResult.Success(listOf(addonGroup)), result)
    }

    @Test
    fun `collapsed group shows one match and a show more card`() = runTest {
        val result = merge(flowOf(NetworkResult.Error("no addons")), matches) { found ->
            found?.let { LocalMediaStreams.group(it, expanded = false) { hidden -> "Show $hidden more" } }
        }.last()

        result as NetworkResult.Success
        val names = result.data.single().streams.map { it.name }
        assertEquals(listOf("Local 720p", "Show 2 more"), names)
    }
}
