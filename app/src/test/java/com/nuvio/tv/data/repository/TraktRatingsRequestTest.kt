package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktRatingsRequestTest {
    private val showIds = TraktIdsDto(imdb = "tt0903747", tmdb = 1396)
    private val episode = TraktScrobbleItem.Episode(
        showTitle = "Breaking Bad",
        showYear = 2008,
        showIds = showIds,
        season = 2,
        number = 5,
        episodeTitle = null
    )
    private val movie = TraktScrobbleItem.Movie(title = "Heat", year = 1995, ids = TraktIdsDto(imdb = "tt0113277"))

    @Test
    fun `episode rating is sent through show ids with season and episode`() {
        val body = buildRatingsRequest(episode, rating = 3)
        assertNull(body.movies)
        val show = body.shows!!.single()
        assertEquals(showIds, show.ids)
        val season = show.seasons.single()
        assertEquals(2, season.number)
        assertEquals(5, season.episodes.single().number)
        assertEquals(3, season.episodes.single().rating)
    }

    @Test
    fun `removal body carries no rating`() {
        val body = buildRatingsRequest(movie, rating = null)
        assertNull(body.movies!!.single().rating)
        assertEquals("tt0113277", body.movies.single().ids.imdb)
    }

    @Test
    fun `rated episode matches on any shared show id and the episode number`() {
        val rated = TraktRatedItemDto(
            rating = 4,
            show = TraktShowDto(ids = TraktIdsDto(trakt = 1, tmdb = 1396)),
            episode = TraktEpisodeDto(season = 2, number = 5)
        )
        assertTrue(rated.matches(episode))
        assertFalse(rated.copy(episode = TraktEpisodeDto(season = 2, number = 6)).matches(episode))
        assertFalse(rated.matches(movie))
    }

    @Test
    fun `rated movie matches by imdb id`() {
        val rated = TraktRatedItemDto(rating = 5, movie = TraktMovieDto(ids = TraktIdsDto(imdb = "tt0113277")))
        assertTrue(rated.matches(movie))
        assertEquals("movies", movie.ratingsType())
        assertEquals("episodes", episode.ratingsType())
    }
}
