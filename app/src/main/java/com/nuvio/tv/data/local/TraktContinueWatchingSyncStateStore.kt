package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Per-profile memory of how local Continue Watching entries relate to Trakt, keyed by item key and
 * storing the local `lastWatched` version:
 * - confirmed: Trakt was seen holding that local version (so if Trakt later drops the item it was
 *   removed/finished there and the local copy must not resurrect it);
 * - pushed: that local version was already sent to Trakt (or rejected by it), so it is never
 *   pushed twice.
 */
@Singleton
class TraktContinueWatchingSyncStateStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "trakt_cw_sync_state"
        const val CONFIRMED_PREFIX = "confirmed|"
        const val PUSHED_PREFIX = "pushed|"
        const val RETENTION_MS = 120L * 24L * 60L * 60L * 1000L
    }

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val confirmedVersions: Flow<Map<String, Long>> =
        profileManager.activeProfileId.flatMapLatest { profileId -> confirmedVersions(profileId) }

    fun confirmedVersions(profileId: Int): Flow<Map<String, Long>> =
        store(profileId).data.map { it.versionsWithPrefix(CONFIRMED_PREFIX) }.distinctUntilChanged()

    suspend fun pushedVersions(profileId: Int): Map<String, Long> =
        store(profileId).data.first().versionsWithPrefix(PUSHED_PREFIX)

    suspend fun markConfirmed(profileId: Int, versions: Map<String, Long>) =
        write(profileId, CONFIRMED_PREFIX, versions)

    suspend fun markPushed(profileId: Int, itemKey: String, version: Long) =
        write(profileId, PUSHED_PREFIX, mapOf(itemKey to version))

    private suspend fun write(profileId: Int, prefix: String, versions: Map<String, Long>) {
        if (versions.isEmpty()) return
        val pruneBefore = System.currentTimeMillis() - RETENTION_MS
        store(profileId).edit { prefs ->
            versions.forEach { (itemKey, version) ->
                val key = longPreferencesKey(prefix + itemKey)
                if ((prefs[key] ?: Long.MIN_VALUE) < version) prefs[key] = version
            }
            prefs.asMap().entries
                .filter { (_, value) -> value is Long && value < pruneBefore }
                .map { (key, _) -> longPreferencesKey(key.name) }
                .forEach { key -> prefs.remove(key) }
        }
    }

    private fun Preferences.versionsWithPrefix(prefix: String): Map<String, Long> =
        asMap().entries.mapNotNull { (key, value) ->
            if (key.name.startsWith(prefix) && value is Long) key.name.removePrefix(prefix) to value else null
        }.toMap()
}
