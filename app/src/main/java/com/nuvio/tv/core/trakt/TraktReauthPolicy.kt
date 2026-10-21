package com.nuvio.tv.core.trakt

/**
 * Pure rules for the "log in to Trakt again" prompt. It is shown each time the app starts
 * while a Trakt session that the user connected has ended on its own (expired or revoked
 * token, failed refresh), until Trakt is connected again. Users who never connected Trakt
 * or who logged out themselves never see it.
 */
internal object TraktReauthPolicy {
    /**
     * @param reauthRequired a connected Trakt session ended without an explicit logout.
     * @param isAuthenticated Trakt is currently connected (the user may have reconnected).
     * @param dismissedThisSession the user chose "Later" since the app was opened.
     * @param canConnect a Trakt client id is configured, so the connect screen can work.
     */
    fun shouldPrompt(
        reauthRequired: Boolean,
        isAuthenticated: Boolean,
        dismissedThisSession: Boolean,
        canConnect: Boolean
    ): Boolean = reauthRequired && !isAuthenticated && !dismissedThisSession && canConnect
}
