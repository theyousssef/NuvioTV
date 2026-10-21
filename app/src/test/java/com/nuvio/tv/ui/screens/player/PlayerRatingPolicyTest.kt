package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRatingPolicyTest {

    @Test
    fun `display numbers map to the inverted trakt ratings`() {
        assertEquals(5, PlayerRatingPolicy.displayToTraktRating(6))
        assertEquals(4, PlayerRatingPolicy.displayToTraktRating(7))
        assertEquals(3, PlayerRatingPolicy.displayToTraktRating(8))
        assertEquals(2, PlayerRatingPolicy.displayToTraktRating(9))
        assertEquals(1, PlayerRatingPolicy.displayToTraktRating(10))
        assertNull(PlayerRatingPolicy.displayToTraktRating(5))
        assertNull(PlayerRatingPolicy.displayToTraktRating(11))
    }

    @Test
    fun `trakt ratings map back to display numbers`() {
        assertEquals(6, PlayerRatingPolicy.traktRatingToDisplay(5))
        assertEquals(7, PlayerRatingPolicy.traktRatingToDisplay(4))
        assertEquals(8, PlayerRatingPolicy.traktRatingToDisplay(3))
        assertEquals(9, PlayerRatingPolicy.traktRatingToDisplay(2))
        assertEquals(10, PlayerRatingPolicy.traktRatingToDisplay(1))
    }

    @Test
    fun `trakt ratings outside one to five select nothing`() {
        assertNull(PlayerRatingPolicy.traktRatingToDisplay(null))
        assertNull(PlayerRatingPolicy.traktRatingToDisplay(0))
        assertNull(PlayerRatingPolicy.traktRatingToDisplay(6))
        assertNull(PlayerRatingPolicy.traktRatingToDisplay(10))
    }

    @Test
    fun `mapping round trips for every display number`() {
        PlayerRatingPolicy.DISPLAY_VALUES.forEach { display ->
            assertEquals(display, PlayerRatingPolicy.traktRatingToDisplay(PlayerRatingPolicy.displayToTraktRating(display)))
        }
    }

    @Test
    fun `pause button always prompts for rateable items`() {
        assertTrue(PlayerRatingPolicy.shouldPromptForRating(PlayerRatingPromptSource.PAUSE_BUTTON, 3f, isRateable = true))
        assertFalse(PlayerRatingPolicy.shouldPromptForRating(PlayerRatingPromptSource.PAUSE_BUTTON, 95f, isRateable = false))
    }

    @Test
    fun `exit next and episode pick prompt only from eighty percent`() {
        listOf(
            PlayerRatingPromptSource.EXIT,
            PlayerRatingPromptSource.NEXT_EPISODE,
            PlayerRatingPromptSource.EPISODE_PICK
        ).forEach { source ->
            assertFalse(PlayerRatingPolicy.shouldPromptForRating(source, 79.9f, isRateable = true))
            assertTrue(PlayerRatingPolicy.shouldPromptForRating(source, 80f, isRateable = true))
            assertTrue(PlayerRatingPolicy.shouldPromptForRating(source, 100f, isRateable = true))
            assertFalse(PlayerRatingPolicy.shouldPromptForRating(source, 100f, isRateable = false))
        }
    }

    @Test
    fun `automatic actions are never intercepted`() {
        assertFalse(
            PlayerRatingPolicy.shouldPromptForRating(
                PlayerRatingPromptSource.NEXT_EPISODE,
                progressPercent = 99f,
                isRateable = true,
                userInitiated = false
            )
        )
    }

    @Test
    fun `back routes by where the window was opened from`() {
        assertEquals(
            PlayerRatingDismissAction.RETURN_TO_PAUSED_PLAYER,
            PlayerRatingPolicy.dismissAction(PlayerRatingPromptSource.PAUSE_BUTTON)
        )
        assertEquals(PlayerRatingDismissAction.EXIT_PLAYER, PlayerRatingPolicy.dismissAction(PlayerRatingPromptSource.EXIT))
        assertEquals(
            PlayerRatingDismissAction.PLAY_NEXT_EPISODE,
            PlayerRatingPolicy.dismissAction(PlayerRatingPromptSource.NEXT_EPISODE)
        )
        assertEquals(
            PlayerRatingDismissAction.PLAY_SELECTED_EPISODE,
            PlayerRatingPolicy.dismissAction(PlayerRatingPromptSource.EPISODE_PICK)
        )
    }

    @Test
    fun `pressing the selected number removes it`() {
        assertEquals(PlayerRatingTapResult.Remove(previous = 8), PlayerRatingPolicy.resolveTap(current = 8, tapped = 8))
    }

    @Test
    fun `pressing another number replaces the selection`() {
        assertEquals(PlayerRatingTapResult.Select(value = 9, replaced = 8), PlayerRatingPolicy.resolveTap(current = 8, tapped = 9))
        assertEquals(PlayerRatingTapResult.Select(value = 6, replaced = null), PlayerRatingPolicy.resolveTap(current = null, tapped = 6))
    }

    @Test
    fun `reactions match the numbers`() {
        assertEquals(PlayerRatingReaction.MEH, PlayerRatingPolicy.reactionFor(6))
        assertEquals(PlayerRatingReaction.OKAY, PlayerRatingPolicy.reactionFor(7))
        assertNull(PlayerRatingReaction.OKAY.emoji)
        assertEquals(PlayerRatingReaction.GREAT, PlayerRatingPolicy.reactionFor(8))
        assertEquals(PlayerRatingReaction.SUPERB, PlayerRatingPolicy.reactionFor(9))
        assertEquals(PlayerRatingReaction.NINJA, PlayerRatingPolicy.reactionFor(10))
        assertEquals("🤯", PlayerRatingReaction.NINJA.emoji)
    }
}
