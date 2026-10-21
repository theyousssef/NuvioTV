package com.nuvio.tv.ui.screens.player

/**
 * Pure rules for the in-player rating window (numbers 6..10, synced to Trakt).
 *
 * The Trakt mapping is intentionally inverted (6 -> 5 ... 10 -> 1): that is the
 * mapping the user asked for, do not "fix" it.
 */
internal object PlayerRatingPolicy {

    /** Trakt's watched threshold; the same 80% the scrobble code uses. */
    const val PROMPT_PROGRESS_THRESHOLD_PERCENT = 80f

    /** The numbers shown in the window, left to right. */
    val DISPLAY_VALUES: List<Int> = listOf(6, 7, 8, 9, 10)

    /** Display number (6..10) -> Trakt rating (5..1). Null for anything else. */
    fun displayToTraktRating(display: Int): Int? =
        if (display in 6..10) 11 - display else null

    /** Trakt rating (5..1) -> display number (6..10). Ratings outside 1..5 show nothing selected. */
    fun traktRatingToDisplay(traktRating: Int?): Int? =
        if (traktRating != null && traktRating in 1..5) 11 - traktRating else null

    /**
     * Whether a user action should open the rating window first.
     * The pause-screen button always opens it; the other actions only once playback
     * reached the watched threshold, and only when the user triggered them
     * (an autoplay countdown that runs out on its own is never intercepted).
     */
    fun shouldPromptForRating(
        source: PlayerRatingPromptSource,
        progressPercent: Float,
        isRateable: Boolean,
        userInitiated: Boolean = true
    ): Boolean {
        if (!isRateable) return false
        return when (source) {
            PlayerRatingPromptSource.PAUSE_BUTTON -> true
            PlayerRatingPromptSource.EXIT,
            PlayerRatingPromptSource.NEXT_EPISODE,
            PlayerRatingPromptSource.EPISODE_PICK ->
                userInitiated && progressPercent >= PROMPT_PROGRESS_THRESHOLD_PERCENT
        }
    }

    /** What Back does when it closes the window, by where the window was opened from. */
    fun dismissAction(source: PlayerRatingPromptSource): PlayerRatingDismissAction = when (source) {
        PlayerRatingPromptSource.PAUSE_BUTTON -> PlayerRatingDismissAction.RETURN_TO_PAUSED_PLAYER
        PlayerRatingPromptSource.EXIT -> PlayerRatingDismissAction.EXIT_PLAYER
        PlayerRatingPromptSource.NEXT_EPISODE -> PlayerRatingDismissAction.PLAY_NEXT_EPISODE
        PlayerRatingPromptSource.EPISODE_PICK -> PlayerRatingDismissAction.PLAY_SELECTED_EPISODE
    }

    /** Pressing the selected number clears it; pressing another number replaces it. */
    fun resolveTap(current: Int?, tapped: Int): PlayerRatingTapResult =
        if (current == tapped) {
            PlayerRatingTapResult.Remove(previous = tapped)
        } else {
            PlayerRatingTapResult.Select(value = tapped, replaced = current)
        }

    fun reactionFor(display: Int): PlayerRatingReaction? = when (display) {
        6 -> PlayerRatingReaction.MEH
        7 -> PlayerRatingReaction.OKAY
        8 -> PlayerRatingReaction.GREAT
        9 -> PlayerRatingReaction.SUPERB
        10 -> PlayerRatingReaction.NINJA
        else -> null
    }
}

enum class PlayerRatingPromptSource {
    /** The "Rate" button on the paused controls. */
    PAUSE_BUTTON,
    /** Back pressed to leave the player. */
    EXIT,
    /** Next-episode button / card / end-of-episode prompt pressed by the user. */
    NEXT_EPISODE,
    /** A different episode picked from the in-player episodes list. */
    EPISODE_PICK
}

enum class PlayerRatingDismissAction {
    RETURN_TO_PAUSED_PLAYER,
    EXIT_PLAYER,
    PLAY_NEXT_EPISODE,
    PLAY_SELECTED_EPISODE
}

sealed interface PlayerRatingTapResult {
    data class Select(val value: Int, val replaced: Int?) : PlayerRatingTapResult
    data class Remove(val previous: Int) : PlayerRatingTapResult
}

/** Reaction shown after a number is picked. [emoji] is null when the message has none. */
enum class PlayerRatingReaction(val emoji: String?) {
    MEH("👎"),
    OKAY(null),
    GREAT("👍"),
    SUPERB("👏"),
    NINJA("🤯")
}
