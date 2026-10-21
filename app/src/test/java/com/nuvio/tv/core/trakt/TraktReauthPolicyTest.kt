package com.nuvio.tv.core.trakt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktReauthPolicyTest {

    private fun prompt(
        reauthRequired: Boolean = true,
        isAuthenticated: Boolean = false,
        dismissedThisSession: Boolean = false,
        canConnect: Boolean = true
    ) = TraktReauthPolicy.shouldPrompt(reauthRequired, isAuthenticated, dismissedThisSession, canConnect)

    @Test
    fun `prompts when a connected trakt session expired`() {
        assertTrue(prompt())
    }

    @Test
    fun `never prompts users who never connected or logged out themselves`() {
        assertFalse(prompt(reauthRequired = false))
    }

    @Test
    fun `stops prompting once trakt is connected again`() {
        assertFalse(prompt(isAuthenticated = true))
    }

    @Test
    fun `later hides it for the rest of the session`() {
        assertFalse(prompt(dismissedThisSession = true))
    }

    @Test
    fun `no prompt without a trakt client id to connect with`() {
        assertFalse(prompt(canConnect = false))
    }
}
