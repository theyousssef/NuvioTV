package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktContinueWatchingSyncStateStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.WatchProgress
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Background companion of the merged Continue Watching list while Trakt is the progress source:
 * - remembers which local versions Trakt holds (so removals made on Trakt are respected);
 * - pushes local progress that Trakt is missing or has an older version of, via `scrobble/pause`.
 *
 * Each local version is pushed at most once, a pass sends at most [MAX_PUSHES_PER_PASS] requests
 * spaced by [PUSH_SPACING_MS], and transient failures back off exponentially.
 */
@Singleton
class TraktContinueWatchingSyncService @Inject constructor(
    private val progressService: TraktProgressService,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val scrobbler: TraktTrackingScrobbler,
    private val scrobbleService: TraktScrobbleService,
    private val syncStateStore: TraktContinueWatchingSyncStateStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val authDataStore: TraktAuthDataStore,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val TAG = "TraktCwSync"
        const val PASS_DEBOUNCE_MS = 5_000L
        const val TICK_MS = 3 * 60_000L
        const val MAX_PUSHES_PER_PASS = 5
        const val PUSH_SPACING_MS = 3_000L
        const val MAX_PUSH_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val MIN_BACKOFF_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 60 * 60_000L
    }

    private data class Pass(
        val profileId: Int,
        val remote: List<WatchProgress>,
        val local: List<WatchProgress>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var retryNotBeforeMs = 0L
    @Volatile private var backoffMs = MIN_BACKOFF_MS

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(TICK_MS)
            }
        }
        scope.launch {
            profileManager.activeProfileId.flatMapLatest { profileId ->
                combine(
                    traktSettingsDataStore.watchProgressSource,
                    authDataStore.isEffectivelyAuthenticated
                ) { source, authenticated -> source == WatchProgressSource.TRAKT && authenticated }
                    .distinctUntilChanged()
                    .flatMapLatest { active ->
                        if (!active) {
                            emptyFlow()
                        } else {
                            combine(
                                progressService.observeRemoteSnapshot().filterNotNull(),
                                watchProgressPreferences.observeAllProgress(profileId),
                                ticker
                            ) { remote, local, _ -> Pass(profileId, remote, local) }
                        }
                    }
            }
                .debounce(PASS_DEBOUNCE_MS)
                .conflate()
                .collect { pass ->
                    try {
                        runPass(pass)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Continue Watching sync pass failed", e)
                    }
                }
        }
    }

    private suspend fun runPass(pass: Pass) {
        val profileId = pass.profileId
        if (profileManager.activeProfileId.value != profileId) return
        val remoteByKey = newestByKey(pass.remote)
        val localByKey = newestByKey(pass.local)

        val confirmed = syncStateStore.confirmedVersions(profileId).first()
        val newlyConfirmed = localByKey.filter { (key, local) ->
            (confirmed[key] ?: Long.MIN_VALUE) < local.lastWatched &&
                isLocalVersionOnRemote(local, remoteByKey[key])
        }.mapValues { (_, local) -> local.lastWatched }
        if (newlyConfirmed.isNotEmpty()) {
            syncStateStore.markConfirmed(profileId, newlyConfirmed)
        }

        val now = System.currentTimeMillis()
        if (now < retryNotBeforeMs) return
        val pushed = syncStateStore.pushedVersions(profileId)
        val daysCapCutoff = traktContinueWatchingCutoffEpochMs(
            traktSettingsDataStore.continueWatchingDaysCap.first(),
            now
        )
        val oldestAllowed = maxOf(daysCapCutoff ?: Long.MIN_VALUE, now - MAX_PUSH_AGE_MS)
        val candidates = localByKey.filter { (key, local) ->
            shouldPushLocalProgressToTrakt(
                local = local,
                remote = remoteByKey[key],
                lastPushedVersion = pushed[key],
                confirmedVersion = maxOf(confirmed[key] ?: Long.MIN_VALUE, newlyConfirmed[key] ?: Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE },
                nowMs = now,
                oldestAllowedMs = oldestAllowed
            )
        }.entries.sortedByDescending { it.value.lastWatched }.take(MAX_PUSHES_PER_PASS)
        if (candidates.isEmpty()) return

        var storedAny = false
        for ((index, entry) in candidates.withIndex()) {
            if (profileManager.activeProfileId.value != profileId) return
            if (index > 0) delay(PUSH_SPACING_MS)
            val (key, local) = entry
            val percent = local.progressPercentage * 100f
            val item = scrobbler.buildScrobbleItem(
                buildTrackingMediaReference(
                    contentType = local.contentType,
                    parentMetaId = local.contentId,
                    videoId = local.videoId,
                    title = local.name,
                    seasonNumber = local.season,
                    episodeNumber = local.episode,
                    episodeTitle = local.episodeTitle
                )
            )
            if (item == null) {
                Log.d(TAG, "No Trakt identity for $key; not pushing")
                syncStateStore.markPushed(profileId, key, local.lastWatched)
                continue
            }
            when (scrobbleService.pushPausedProgress(item, percent)) {
                TraktPlaybackPushResult.STORED -> {
                    syncStateStore.markPushed(profileId, key, local.lastWatched)
                    storedAny = true
                    backoffMs = MIN_BACKOFF_MS
                }
                TraktPlaybackPushResult.REJECTED -> {
                    syncStateStore.markPushed(profileId, key, local.lastWatched)
                }
                TraktPlaybackPushResult.RETRY_LATER -> {
                    retryNotBeforeMs = System.currentTimeMillis() + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    break
                }
            }
        }
        if (storedAny) progressService.refreshNow()
    }

    private fun newestByKey(entries: List<WatchProgress>): Map<String, WatchProgress> {
        val result = linkedMapOf<String, WatchProgress>()
        entries.forEach { progress ->
            val key = continueWatchingItemKey(progress)
            val existing = result[key]
            if (existing == null || progress.lastWatched > existing.lastWatched) result[key] = progress
        }
        return result
    }
}
