package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Body for `POST /sync/ratings` and `POST /sync/ratings/remove`. */
@JsonClass(generateAdapter = true)
data class TraktRatingsRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingShowDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingMovieDto(
    @Json(name = "ids") val ids: TraktIdsDto,
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "rating") val rating: Int? = null
)

/** A show that only carries the seasons/episodes being (un)rated — the show itself is not rated. */
@JsonClass(generateAdapter = true)
data class TraktRatingShowDto(
    @Json(name = "ids") val ids: TraktIdsDto,
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "seasons") val seasons: List<TraktRatingSeasonDto>
)

@JsonClass(generateAdapter = true)
data class TraktRatingSeasonDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktRatingEpisodeDto>
)

@JsonClass(generateAdapter = true)
data class TraktRatingEpisodeDto(
    @Json(name = "number") val number: Int,
    @Json(name = "rating") val rating: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsMutationResponseDto(
    @Json(name = "added") val added: TraktHistoryRemoveCountDto? = null,
    @Json(name = "deleted") val deleted: TraktHistoryRemoveCountDto? = null
)

/** One row of `GET /sync/ratings/{movies|episodes}`. */
@JsonClass(generateAdapter = true)
data class TraktRatedItemDto(
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)
