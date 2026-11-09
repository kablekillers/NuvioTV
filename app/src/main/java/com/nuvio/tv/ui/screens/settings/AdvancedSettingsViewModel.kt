package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.CacheCleaner
import com.nuvio.tv.CachePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean = false,
    val smoothBringIntoViewEnabled: Boolean = true,
    val composeHighlighterEnabled: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val playerStatsHudEnabled: Boolean = false,
    val rgb565Enabled: Boolean = true,
    val autoClearCacheOnStartup: Boolean = false,
    val smartCacheCleanup: Boolean = true,
    val clearingCache: Boolean = false,
    val cacheClearSucceeded: Boolean? = null,
    val sentryEnabled: Boolean = true
)

sealed class AdvancedSettingsEvent {
    data class SetAutoClearCacheOnStartup(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmartCacheCleanup(val enabled: Boolean) : AdvancedSettingsEvent()
    data object ClearCacheNow : AdvancedSettingsEvent()
    data class SetFastHorizontalNavigationEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmoothBringIntoViewEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetComposeHighlighterEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlaybackIssueReportsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlayerStatsHudEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetRgb565Enabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSentryEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val sentrySettingsDataStore: SentrySettingsDataStore,
    private val imagePerformancePreferences: ImagePerformancePreferences,
    @ApplicationContext private val context: Context,
    private val cacheCleaner: CacheCleaner,
    private val appRestarter: AppRestarter
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                autoClearCacheOnStartup = CachePreferences.autoClearOnStartup(context),
                smartCacheCleanup = CachePreferences.smartCleanupEnabled(context)
            )
        }
        _uiState.update { it.copy(rgb565Enabled = imagePerformancePreferences.rgb565Enabled) }
        viewModelScope.launch {
            layoutPreferenceDataStore.fastHorizontalNavigationEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(fastHorizontalNavigationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.smoothBringIntoViewEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(smoothBringIntoViewEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.composeHighlighterEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(composeHighlighterEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { it.copy(playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled) }
            }
        }
        viewModelScope.launch {
            deviceLocalPlayerPreferences.playerStatsHudEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(playerStatsHudEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            sentrySettingsDataStore.enabled.collectLatest { enabled ->
                _uiState.update { it.copy(sentryEnabled = enabled) }
            }
        }
    }

    fun onEvent(event: AdvancedSettingsEvent) {
        when (event) {
            is AdvancedSettingsEvent.SetAutoClearCacheOnStartup -> {
                CachePreferences.setAutoClearOnStartup(context, event.enabled)
                _uiState.update { it.copy(autoClearCacheOnStartup = event.enabled) }
            }
            is AdvancedSettingsEvent.SetSmartCacheCleanup -> {
                CachePreferences.setSmartCleanupEnabled(context, event.enabled)
                _uiState.update { it.copy(smartCacheCleanup = event.enabled) }
            }
            AdvancedSettingsEvent.ClearCacheNow -> {
                if (_uiState.value.clearingCache) return
                _uiState.update { it.copy(clearingCache = true, cacheClearSucceeded = null) }
                cacheCleaner.clearNow { success ->
                    _uiState.update { it.copy(clearingCache = false, cacheClearSucceeded = success) }
                }
            }
            is AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setFastHorizontalNavigationEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setSmoothBringIntoViewEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetComposeHighlighterEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlaybackIssueReportsEnabled -> {
                viewModelScope.launch {
                    playerSettingsDataStore.setPlaybackIssueReportsEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlayerStatsHudEnabled -> {
                viewModelScope.launch {
                    deviceLocalPlayerPreferences.setPlayerStatsHudEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetRgb565Enabled -> {
                if (imagePerformancePreferences.setRgb565Enabled(event.enabled)) {
                    _uiState.update { it.copy(rgb565Enabled = event.enabled) }
                    appRestarter.restart()
                }
            }
            is AdvancedSettingsEvent.SetSentryEnabled -> {
                viewModelScope.launch {
                    sentrySettingsDataStore.setEnabled(event.enabled)
                }
            }
        }
    }
}
