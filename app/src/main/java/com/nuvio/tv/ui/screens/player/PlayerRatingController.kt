package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.util.Log
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.PlayerRatingDataStore
import com.nuvio.tv.data.repository.TraktRatingResult
import com.nuvio.tv.data.repository.TraktRatingsService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktTrackingScrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PlayerRatingSyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    NOT_CONNECTED,
    FAILED
}

data class PlayerRatingUiState(
    val isVisible: Boolean = false,
    val source: PlayerRatingPromptSource? = null,
    val isEpisode: Boolean = false,
    val title: String = "",
    val subtitle: String? = null,
    /** Selected display number (6..10), or null. */
    val selected: Int? = null,
    /** Bumped on every user press so the UI can replay the fill / reaction animation. */
    val selectionNonce: Int = 0,
    val syncStatus: PlayerRatingSyncStatus = PlayerRatingSyncStatus.IDLE,
    /** Small blurred still of the video, stretched behind the window. */
    val backdrop: Bitmap? = null
)

/**
 * Owns the in-player rating window: which item is rated, the current selection, the
 * local copy and the Trakt sync. Trakt writes are serialized and always push the latest
 * selection, so quick re-presses land on Trakt in order.
 */
internal class PlayerRatingController(
    private val playbackController: PlayerRuntimeController,
    private val traktRatingsService: TraktRatingsService,
    private val traktTrackingScrobbler: TraktTrackingScrobbler,
    private val ratingDataStore: PlayerRatingDataStore,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "PlayerRating"
    }

    /** One opening of the window for one item. */
    private class Session(
        val itemKey: String,
        val traktItem: Deferred<TraktScrobbleItem?>
    ) {
        /** Latest Trakt rating wanted (1..5), null = removed. */
        var desiredTraktRating: Int? = null
        var hasUserChange = false
        var lastSyncedTraktRating: Int? = null
        var hasSynced = false
    }

    private val _uiState = MutableStateFlow(PlayerRatingUiState())
    val uiState: StateFlow<PlayerRatingUiState> = _uiState.asStateFlow()

    private val syncMutex = Mutex()
    private var session: Session? = null

    fun isRateable(): Boolean =
        !playbackController.playbackTimeline.value.isLive && playbackController.buildScrobbleItem() != null

    fun shouldPrompt(source: PlayerRatingPromptSource, userInitiated: Boolean = true): Boolean {
        if (_uiState.value.isVisible) return false
        return PlayerRatingPolicy.shouldPromptForRating(
            source = source,
            progressPercent = playbackController.currentPlaybackProgressPercent(),
            isRateable = isRateable(),
            userInitiated = userInitiated
        )
    }

    fun open(source: PlayerRatingPromptSource) {
        val media = playbackController.buildScrobbleItem() ?: return
        val itemKey = media.ratingItemKey()
        val traktItem = scope.async(start = CoroutineStart.LAZY) {
            runCatching { traktTrackingScrobbler.buildScrobbleItem(media) }.getOrNull()
        }
        val newSession = Session(itemKey, traktItem)
        session = newSession

        playbackController.pauseForRatingPrompt()
        val state = playbackController.uiState.value
        val episode = media.episode
        _uiState.value = PlayerRatingUiState(
            isVisible = true,
            source = source,
            isEpisode = episode != null,
            title = if (episode != null) state.contentName ?: state.title else state.title,
            subtitle = episode?.let {
                listOfNotNull(
                    "S${it.season ?: state.currentSeason ?: 0} E${it.number}",
                    state.currentEpisodeTitle?.takeIf(String::isNotBlank)
                ).joinToString(" · ")
            }
        )
        Log.d(TAG, "open source=$source item=$itemKey")

        scope.launch {
            val videoView = playbackController.mpvView
                ?: playbackController.exoPlayerView?.videoSurfaceView
            val frame = PlayerFrameCapture.captureBlurredFrame(videoView)
            if (session === newSession && frame != null) {
                _uiState.update { it.copy(backdrop = frame) }
            }
        }

        scope.launch {
            val local = ratingDataStore.get(itemKey)
            if (session !== newSession || newSession.hasUserChange) return@launch
            _uiState.update { it.copy(selected = local.display) }

            val pending = local.pendingTraktRating
            if (pending != null) {
                // A previous write never reached Trakt: push it now instead of reading.
                newSession.desiredTraktRating = pending.takeIf { it != PlayerRatingDataStore.PENDING_REMOVE }
                syncToTrakt(newSession, quiet = true)
                return@launch
            }
            val item = traktItem.await() ?: return@launch
            when (val result = traktRatingsService.getRating(item)) {
                is TraktRatingResult.Success -> {
                    newSession.lastSyncedTraktRating = result.rating
                    newSession.hasSynced = true
                    if (session !== newSession || newSession.hasUserChange) return@launch
                    val display = PlayerRatingPolicy.traktRatingToDisplay(result.rating)
                    _uiState.update { it.copy(selected = display) }
                    ratingDataStore.setDisplay(itemKey, display)
                }
                TraktRatingResult.NotConnected -> if (session === newSession) {
                    _uiState.update { it.copy(syncStatus = PlayerRatingSyncStatus.NOT_CONNECTED) }
                }
                TraktRatingResult.Failed -> Unit
            }
        }
    }

    /** Closes the window and returns where it was opened from, or null when it was not open. */
    fun dismiss(): PlayerRatingPromptSource? {
        val source = _uiState.value.source.takeIf { _uiState.value.isVisible }
        _uiState.value = PlayerRatingUiState()
        return source
    }

    fun onNumberPressed(display: Int) {
        val current = session ?: return
        val state = _uiState.value
        if (!state.isVisible) return
        val newSelection = when (val tap = PlayerRatingPolicy.resolveTap(state.selected, display)) {
            is PlayerRatingTapResult.Select -> tap.value
            is PlayerRatingTapResult.Remove -> null
        }
        current.hasUserChange = true
        current.desiredTraktRating = newSelection?.let(PlayerRatingPolicy::displayToTraktRating)
        _uiState.update {
            it.copy(selected = newSelection, selectionNonce = it.selectionNonce + 1)
        }
        scope.launch {
            ratingDataStore.setDisplay(current.itemKey, newSelection)
            syncToTrakt(current, quiet = false)
        }
    }

    private suspend fun syncToTrakt(target: Session, quiet: Boolean) {
        syncMutex.withLock {
            val desired = target.desiredTraktRating
            if (target.hasSynced && target.lastSyncedTraktRating == desired) return
            ratingDataStore.setPending(target.itemKey, desired ?: PlayerRatingDataStore.PENDING_REMOVE)
            if (!quiet) setStatus(target, PlayerRatingSyncStatus.SYNCING)
            val item = target.traktItem.await()
            val result = when {
                item == null -> TraktRatingResult.Failed
                desired == null -> traktRatingsService.removeRating(item)
                else -> traktRatingsService.setRating(item, desired)
            }
            Log.d(TAG, "sync ${target.itemKey} trakt=$desired -> $result")
            when (result) {
                is TraktRatingResult.Success -> {
                    target.lastSyncedTraktRating = desired
                    target.hasSynced = true
                    ratingDataStore.setPending(target.itemKey, null)
                    setStatus(target, PlayerRatingSyncStatus.SYNCED)
                }
                TraktRatingResult.NotConnected -> {
                    // Kept on this device only; nothing to retry.
                    ratingDataStore.setPending(target.itemKey, null)
                    setStatus(target, PlayerRatingSyncStatus.NOT_CONNECTED)
                }
                TraktRatingResult.Failed -> {
                    // Stays pending; retried the next time the window opens for this item.
                    if (!quiet) setStatus(target, PlayerRatingSyncStatus.FAILED)
                }
            }
        }
    }

    private fun setStatus(target: Session, status: PlayerRatingSyncStatus) {
        if (session === target && _uiState.value.isVisible) {
            _uiState.update { it.copy(syncStatus = status) }
        }
    }
}

/** Stable local key of the rated movie or episode. */
internal fun TrackingMediaReference.ratingItemKey(): String {
    val ep = episode ?: return stableKey
    return "$stableKey:${ep.season ?: 0}:${ep.number}"
}

/** Pauses playback under the rating window without the pause overlay or controls. */
internal fun PlayerRuntimeController.pauseForRatingPrompt() {
    if (isPlaybackCurrentlyPlaying()) {
        userPausedManually = true
        if (isUsingMpvEngine()) {
            setPlaybackPaused(true)
            stopProgressUpdates()
            stopWatchProgressSaving()
            emitPauseScrobbleForCurrentProgress()
        } else {
            _exoPlayer?.pause()
        }
    }
    cancelPauseOverlay()
    hideControls()
}
