package com.nuvio.tv.updater

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.upstreamReleaseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "upstream_release_watch"
)

data class UpstreamReleaseNotice(
    val tag: String,
    val name: String,
    val publishedAt: String?,
    val url: String
)

/**
 * Checks the latest stable NuvioMedia/NuvioTV release at most once a day and reports it when
 * it is newer than the last one the user was told about. It never downloads anything.
 */
@Singleton
class UpstreamReleaseWatcher @Inject constructor(
    @ApplicationContext context: Context,
    private val gitHubReleaseApi: GitHubReleaseApi
) {
    private val dataStore = context.upstreamReleaseDataStore
    private val lastCheckAtKey = longPreferencesKey("upstream_last_check_at_ms")
    private val lastNotifiedTagKey = stringPreferencesKey("upstream_last_notified_tag")

    /** Returns a notice to show, or null when nothing is due, nothing is new, or the request failed. */
    suspend fun checkForNotice(nowMs: Long = System.currentTimeMillis()): UpstreamReleaseNotice? {
        val prefs = dataStore.data.first()
        if (!UpstreamReleasePolicy.isCheckDue(nowMs, prefs[lastCheckAtKey] ?: 0L)) return null
        // Record the attempt first so a failure waits a day before retrying.
        dataStore.edit { it[lastCheckAtKey] = nowMs }

        val owner = BuildConfig.GITHUB_OWNER
        val repo = BuildConfig.GITHUB_REPO
        val release = try {
            val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
            if (!response.isSuccessful) {
                Log.d(TAG, "Latest release request failed: ${response.code()}")
                return null
            }
            response.body() ?: return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Latest release request failed", e)
            return null
        }
        if (release.draft || release.prerelease) return null

        val tag = release.tagName?.takeIf { it.isNotBlank() } ?: return null
        if (!UpstreamReleasePolicy.shouldNotify(tag, prefs[lastNotifiedTagKey])) return null

        return UpstreamReleaseNotice(
            tag = tag,
            name = release.name?.takeIf { it.isNotBlank() } ?: tag,
            publishedAt = release.publishedAt?.takeIf { it.length >= 10 }?.substring(0, 10),
            url = UpstreamReleasePolicy.releaseUrl(owner, repo, tag)
        )
    }

    /** Remembers [tag] so the notice never shows again for it (or anything older). */
    suspend fun markNotified(tag: String) {
        dataStore.edit { it[lastNotifiedTagKey] = tag }
    }

    private companion object {
        const val TAG = "UpstreamReleaseWatcher"
    }
}
