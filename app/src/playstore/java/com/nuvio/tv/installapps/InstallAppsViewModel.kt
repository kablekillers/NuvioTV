package com.nuvio.tv.installapps

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Play Store builds can't install other apps, so this screen stays empty there. */
@HiltViewModel
class InstallAppsViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(InstallAppsUiState())
    val uiState: StateFlow<InstallAppsUiState> = _uiState.asStateFlow()

    fun refresh() = Unit
    fun install(app: InstallableApp) = Unit
    fun installFromUrl(rawUrl: String) = Unit
    fun retryPendingInstall() = Unit
    fun openUnknownSourcesSettings() = Unit
    fun dismissUnknownSourcesDialog() = Unit
    fun consumeMessage() = Unit
}
