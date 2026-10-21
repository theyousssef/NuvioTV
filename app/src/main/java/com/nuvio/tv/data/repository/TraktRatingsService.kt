package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingSeasonDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a Trakt rating read/write. */
sealed interface TraktRatingResult {
    data class Success(val rating: Int?) : TraktRatingResult
    data object NotConnected : TraktRatingResult
    data object Failed : TraktRatingResult
}

/**
 * Reads and writes the user's own Trakt ratings for movies and single episodes
 * (`/sync/ratings`). Episode ratings are addressed by show ids + season/episode number,
 * so the show itself is never rated.
 */
@Singleton
class TraktRatingsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "TraktRatingsSvc"
        private const val CACHE_TTL_MS = 5 * 60_000L
    }

    private data class RatingsCache(
        val profileId: Int,
        val fetchedAtMs: Long,
        val items: List<TraktRatedItemDto>
    )

    private val cacheMutex = Mutex()
    private val caches = mutableMapOf<String, RatingsCache>()

    suspend fun isConnected(): Boolean =
        traktAuthService.hasRequiredCredentials() &&
            traktAuthService.getCurrentAuthState().isAuthenticated

    /** The user's Trakt rating (1..10) for [item], or null when unrated. */
    suspend fun getRating(item: TraktScrobbleItem): TraktRatingResult {
        if (!isConnected()) return TraktRatingResult.NotConnected
        val type = item.ratingsType()
        val items = cacheMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val cached = caches[type]
            if (cached != null && cached.profileId == profileId &&
                System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS
            ) {
                cached.items
            } else {
                val response = try {
                    traktAuthService.executeAuthorizedRequest { auth -> traktApi.getRatings(auth, type) }
                } catch (e: IOException) {
                    Log.w(TAG, "GET sync/ratings/$type failed", e)
                    null
                }
                Log.d(TAG, "GET sync/ratings/$type -> HTTP ${response?.code()} (${response?.body()?.size ?: 0} items)")
                val body = response?.takeIf { it.isSuccessful }?.body() ?: return TraktRatingResult.Failed
                caches[type] = RatingsCache(profileId, System.currentTimeMillis(), body)
                body
            }
        }
        return TraktRatingResult.Success(items.firstOrNull { it.matches(item) }?.rating)
    }

    /** Sets [rating] (1..10) on Trakt; an existing rating is overwritten. */
    suspend fun setRating(item: TraktScrobbleItem, rating: Int): TraktRatingResult {
        if (!isConnected()) return TraktRatingResult.NotConnected
        val body = buildRatingsRequest(item, rating)
        val response = try {
            traktAuthService.executeAuthorizedWriteRequest { auth -> traktApi.addRatings(auth, body) }
        } catch (e: IOException) {
            Log.w(TAG, "POST sync/ratings failed for ${item.itemKey}", e)
            null
        }
        Log.d(TAG, "POST sync/ratings ${item.itemKey} rating=$rating -> HTTP ${response?.code()} added=${response?.body()?.added}")
        if (response?.isSuccessful != true) return TraktRatingResult.Failed
        updateCache(item, rating)
        return TraktRatingResult.Success(rating)
    }

    /** Removes the user's Trakt rating for [item]. */
    suspend fun removeRating(item: TraktScrobbleItem): TraktRatingResult {
        if (!isConnected()) return TraktRatingResult.NotConnected
        val body = buildRatingsRequest(item, rating = null)
        val response = try {
            traktAuthService.executeAuthorizedWriteRequest { auth -> traktApi.removeRatings(auth, body) }
        } catch (e: IOException) {
            Log.w(TAG, "POST sync/ratings/remove failed for ${item.itemKey}", e)
            null
        }
        Log.d(TAG, "POST sync/ratings/remove ${item.itemKey} -> HTTP ${response?.code()} deleted=${response?.body()?.deleted}")
        if (response?.isSuccessful != true) return TraktRatingResult.Failed
        updateCache(item, rating = null)
        return TraktRatingResult.Success(null)
    }

    private suspend fun updateCache(item: TraktScrobbleItem, rating: Int?) {
        cacheMutex.withLock {
            val type = item.ratingsType()
            val cached = caches[type] ?: return
            val others = cached.items.filterNot { it.matches(item) }
            val updated = if (rating == null) others else others + item.toRatedItem(rating)
            caches[type] = cached.copy(items = updated)
        }
    }
}

internal fun TraktScrobbleItem.ratingsType(): String = when (this) {
    is TraktScrobbleItem.Movie -> "movies"
    is TraktScrobbleItem.Episode -> "episodes"
}

/** Request body for one movie or one episode; [rating] null builds a removal body. */
internal fun buildRatingsRequest(item: TraktScrobbleItem, rating: Int?): TraktRatingsRequestDto = when (item) {
    is TraktScrobbleItem.Movie -> TraktRatingsRequestDto(
        movies = listOf(TraktRatingMovieDto(ids = item.ids, title = item.title, year = item.year, rating = rating))
    )
    is TraktScrobbleItem.Episode -> TraktRatingsRequestDto(
        shows = listOf(
            TraktRatingShowDto(
                ids = item.showIds,
                title = item.showTitle,
                year = item.showYear,
                seasons = listOf(
                    TraktRatingSeasonDto(
                        number = item.season,
                        episodes = listOf(TraktRatingEpisodeDto(number = item.number, rating = rating))
                    )
                )
            )
        )
    )
}

internal fun TraktRatedItemDto.matches(item: TraktScrobbleItem): Boolean = when (item) {
    is TraktScrobbleItem.Movie -> movie?.ids.sharesIdWith(item.ids)
    is TraktScrobbleItem.Episode -> episode?.season == item.season &&
        episode.number == item.number &&
        show?.ids.sharesIdWith(item.showIds)
}

private fun TraktScrobbleItem.toRatedItem(rating: Int): TraktRatedItemDto = when (this) {
    is TraktScrobbleItem.Movie -> TraktRatedItemDto(
        rating = rating,
        type = "movie",
        movie = com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto(title = title, year = year, ids = ids)
    )
    is TraktScrobbleItem.Episode -> TraktRatedItemDto(
        rating = rating,
        type = "episode",
        show = com.nuvio.tv.data.remote.dto.trakt.TraktShowDto(title = showTitle, year = showYear, ids = showIds),
        episode = com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto(season = season, number = number)
    )
}

private fun TraktIdsDto?.sharesIdWith(other: TraktIdsDto): Boolean {
    if (this == null) return false
    return (trakt != null && trakt == other.trakt) ||
        (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (tvdb != null && tvdb == other.tvdb)
}
