package com.nuvio.tv.core.trakt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.repository.TraktAuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Activity-scoped, so "Later" lasts until the main activity is created again (the next time
 * the app is opened), and the prompt comes back on every fresh start while Trakt needs a login.
 */
@HiltViewModel
class TraktReauthViewModel @Inject constructor(
    authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    traktAuthDataStore: TraktAuthDataStore,
    traktAuthService: TraktAuthService
) : ViewModel() {

    private val dismissedThisSession = MutableStateFlow(false)
    private val canConnect = traktAuthService.hasRequiredCredentials()

    val showPrompt: StateFlow<Boolean> = combine(
        authSessionNoticeDataStore.traktReauthRequired,
        traktAuthDataStore.isAuthenticated,
        dismissedThisSession
    ) { reauthRequired, isAuthenticated, dismissed ->
        TraktReauthPolicy.shouldPrompt(
            reauthRequired = reauthRequired,
            isAuthenticated = isAuthenticated,
            dismissedThisSession = dismissed,
            canConnect = canConnect
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "Later", or leaving for the connect screen: hide it for the rest of this session. */
    fun dismissForSession() {
        dismissedThisSession.value = true
    }
}
