package com.nuvio.tv.core.localmedia

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.model.AddonStreams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds the "Local files" group to any addon stream search, so the stream screen and the
 * player's source and episode panels all find files on the device.
 */
@Singleton
class LocalMediaStreamSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LocalMediaLibrary
) {

    /**
     * Puts the local group in front of the addon results. The device scan runs alongside the
     * addon requests, and a local match is still shown when every online source fails.
     *
     * [group] turns the latest matches (null while the scan is running) into the group to show;
     * by default every match is listed, with no "show more" card.
     */
    fun withLocalMedia(
        remote: Flow<NetworkResult<List<AddonStreams>>>,
        query: LocalMediaQuery,
        forceRefresh: Boolean = false,
        group: (List<LocalMediaMatch>?) -> AddonStreams? = { matches -> matches?.let { LocalMediaStreams.group(it) } }
    ): Flow<NetworkResult<List<AddonStreams>>> {
        if (!library.hasPermission()) return remote
        if (forceRefresh) library.invalidate()
        val local = flow<List<LocalMediaMatch>?> {
            emit(null)
            emit(library.findMatches(query))
        }
        return prependLocalGroup(remote, local, group) { message ->
            context.withAppLocale().getString(R.string.local_media_no_match, message)
        }
    }
}

/** Combines addon results with local matches; see [LocalMediaStreamSource.withLocalMedia]. */
internal fun prependLocalGroup(
    remote: Flow<NetworkResult<List<AddonStreams>>>,
    local: Flow<List<LocalMediaMatch>?>,
    group: (List<LocalMediaMatch>?) -> AddonStreams?,
    noMatchMessage: (String) -> String
): Flow<NetworkResult<List<AddonStreams>>> = combine(remote, local) { result, matches ->
    val localGroup = group(matches)
    when {
        // An addon error ("no installed addon supports...") is not final until the device scan is done.
        localGroup == null && matches == null && result is NetworkResult.Error -> NetworkResult.Loading
        // Say that local files were searched too, not only that addons are missing.
        localGroup == null && result is NetworkResult.Error -> result.copy(message = noMatchMessage(result.message))
        localGroup == null -> result
        result is NetworkResult.Success -> NetworkResult.Success(listOf(localGroup) + result.data)
        else -> NetworkResult.Success(listOf(localGroup))
    }
}
