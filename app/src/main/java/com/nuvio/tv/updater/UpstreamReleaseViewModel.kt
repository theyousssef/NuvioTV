package com.nuvio.tv.updater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpstreamReleaseViewModel @Inject constructor(
    private val watcher: UpstreamReleaseWatcher
) : ViewModel() {

    private val _notice = MutableStateFlow<UpstreamReleaseNotice?>(null)
    val notice: StateFlow<UpstreamReleaseNotice?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            _notice.value = watcher.checkForNotice()
        }
    }

    fun acknowledge() {
        val tag = _notice.value?.tag ?: return
        _notice.value = null
        viewModelScope.launch { watcher.markNotified(tag) }
    }
}
