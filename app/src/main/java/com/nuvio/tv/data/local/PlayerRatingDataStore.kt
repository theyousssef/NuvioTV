package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local copy of the ratings picked in the player's rating window, per profile, so the
 * window can show the current rating instantly and keep it when Trakt is not connected
 * or unreachable.
 *
 * Values are the displayed numbers (6..10). A pending Trakt write is kept next to it
 * (Trakt rating 1..5, or [PENDING_REMOVE]) until Trakt confirms it.
 */
@Singleton
class PlayerRatingDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "player_ratings"
        const val PENDING_REMOVE = 0
    }

    data class Entry(val display: Int?, val pendingTraktRating: Int?)

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun displayKey(itemKey: String) = intPreferencesKey("r_$itemKey")
    private fun pendingKey(itemKey: String) = intPreferencesKey("p_$itemKey")

    suspend fun get(itemKey: String): Entry {
        val prefs = store().data.first()
        return Entry(display = prefs[displayKey(itemKey)], pendingTraktRating = prefs[pendingKey(itemKey)])
    }

    suspend fun setDisplay(itemKey: String, display: Int?) {
        store().edit { prefs ->
            if (display == null) prefs.remove(displayKey(itemKey)) else prefs[displayKey(itemKey)] = display
        }
    }

    /** Records the Trakt write still to be done; null clears it. */
    suspend fun setPending(itemKey: String, traktRatingOrRemove: Int?) {
        store().edit { prefs ->
            if (traktRatingOrRemove == null) {
                prefs.remove(pendingKey(itemKey))
            } else {
                prefs[pendingKey(itemKey)] = traktRatingOrRemove
            }
        }
    }
}
