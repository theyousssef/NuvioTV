package com.nuvio.tv.core.localmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaMatcherTest {

    private val got = LocalMediaQuery(titles = listOf("Game of Thrones"), year = 2011, season = 1, episode = 1)

    private fun file(path: String, size: Long? = null): LocalVideoFile {
        val relative = path.substringBeforeLast('/', "")
        return LocalVideoFile(uri = "file:///$path", relativePath = relative, fileName = path.substringAfterLast('/'), sizeBytes = size)
    }

    private fun bestFor(query: LocalMediaQuery, vararg paths: String): String? =
        LocalMediaMatcher.rank(query, paths.map { file(it) }).firstOrNull()?.file?.fileName

    @Test
    fun `matches the user's example names for the same episode`() {
        listOf(
            "Movies/GOT - s01e01.mkv",
            "Movies/game.of.thrones.s01e01.mkv",
            "Movies/game of thrones (2011) blabla s01 e01 bluray.mkv",
            "Movies/GOT/S01/1.mkv",
            "Movies/Game of Thrones/Season 1/01 - Winter Is Coming.mkv",
            "Game.of.Thrones.S01.1080p.BluRay/Game.of.Thrones.S01E01.Winter.Is.Coming.mkv",
            "Shows/Game of Thrones/Season 01/Episode 1.mp4",
            "Game of Thrones/1x01.avi",
            "Game of Thrones/S01E01.mkv"
        ).forEach { path ->
            assertEquals(path, path.substringAfterLast('/'), bestFor(got, path))
        }
    }

    @Test
    fun `rejects other episodes and seasons`() {
        assertNull(
            bestFor(
                got,
                "Movies/GOT - s01e02.mkv",
                "Movies/game.of.thrones.s02e01.mkv",
                "Movies/GOT/S01/10.mkv",
                "Movies/GOT/S02/1.mkv"
            )
        )
    }

    @Test
    fun `rejects other shows with the same episode number`() {
        assertNull(bestFor(got, "Movies/Breaking.Bad.S01E01.mkv", "House of the Dragon/S01/1.mkv"))
    }

    @Test
    fun `skips samples`() {
        assertNull(bestFor(got, "Movies/Game.of.Thrones.S01E01.sample.mkv"))
    }

    @Test
    fun `prefers the higher resolution when names score the same`() {
        assertEquals(
            "Game.of.Thrones.S01E01.2160p.mkv",
            bestFor(got, "Game.of.Thrones.S01E01.720p.mkv", "Game.of.Thrones.S01E01.2160p.mkv")
        )
    }

    @Test
    fun `movies ignore episode files and respect the year`() {
        val dune = LocalMediaQuery(titles = listOf("Dune"), year = 2021, season = null, episode = null)
        assertEquals(
            "Dune.2021.2160p.WEB-DL.mkv",
            bestFor(dune, "Movies/Dune.1984.1080p.BluRay.mkv", "Movies/Dune.2021.2160p.WEB-DL.mkv", "Dune/S01E01.mkv")
        )
        assertNull(bestFor(dune, "Movies/Dune.1984.1080p.BluRay.mkv"))
    }

    @Test
    fun `movie title from folder when file name is generic`() {
        val inception = LocalMediaQuery(titles = listOf("Inception"), year = 2010, season = null, episode = null)
        assertEquals("movie.mkv", bestFor(inception, "Movies/Inception (2010)/movie.mkv"))
    }

    @Test
    fun `numbers inside titles are not mistaken for years or seasons`() {
        val bladeRunner = LocalMediaQuery(titles = listOf("Blade Runner 2049"), year = 2017, season = null, episode = null)
        assertEquals("Blade Runner 2049 (2017).mkv", bestFor(bladeRunner, "Movies/Blade Runner 2049 (2017).mkv"))

        val oceans = LocalMediaQuery(titles = listOf("Ocean's Eleven"), year = 2001, season = null, episode = null)
        val parsed = LocalMediaNameParser.parse("Movies", "Ocean's 11 (2001).mkv")
        assertNull(parsed.season)
        assertNull(LocalMediaMatcher.rank(oceans, listOf(file("Movies/Ocean's 12 (2004).mkv"))).firstOrNull())
    }

    @Test
    fun `parser reads season from folder and title from show folder`() {
        val parsed = LocalMediaNameParser.parse("USB/TV/GOT/S01", "1.mkv")
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals(listOf("GOT"), parsed.titleCandidates)
    }

    @Test
    fun `title score handles acronyms, punctuation and small typos`() {
        assertTrue(LocalMediaMatcher.titleScore("How I Met Your Mother", "HIMYM") >= 0.9)
        assertTrue(LocalMediaMatcher.titleScore("Marvel's Agents of S.H.I.E.L.D.", "Marvels Agents of SHIELD") >= 0.9)
        assertTrue(LocalMediaMatcher.titleScore("The Office", "Office") == 1.0)
        assertTrue(LocalMediaMatcher.titleScore("Interstellar", "Interstelar") > 0.9)
        assertTrue(LocalMediaMatcher.titleScore("Game of Thrones", "Thrones") < LocalMediaMatcher.MIN_SCORE)
    }
}
