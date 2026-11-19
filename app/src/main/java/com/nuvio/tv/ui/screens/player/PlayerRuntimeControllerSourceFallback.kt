package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_SOURCE_FALLBACKS = 3
private const val SOURCE_SEARCH_WAIT_MS = 15_000L
private const val SOURCE_SEARCH_POLL_MS = 500L

/**
 * Last step before a startup error: quietly play the next source from the same list the Sources
 * panel shows, up to [MAX_SOURCE_FALLBACKS] times. Runs only before the first frame, after the
 * engine switch, retries and server fallback have had their turn.
 *
 * Returns true when a fallback was started and the error must not be shown.
 */
internal fun PlayerRuntimeController.tryNextSourceFallback(): Boolean {
    if (hasRenderedFirstFrame) return false
    if (sourceFallbackJob?.isActive == true) return true
    if (sourceFallbackAttempts >= MAX_SOURCE_FALLBACKS) return false
    val failedUrl = currentStreamUrl
    if (failedUrl.isBlank()) return false
    sourceFallbackTriedUrls += failedUrl

    sourceFallbackJob = scope.launch {
        showRecoveryOverlay()
        // Reuses the Sources panel search, so results already found are used right away.
        loadSourceStreams(forceRefresh = false)
        var waited = 0L
        var next = nextFallbackCandidate()
        while (next == null && waited < SOURCE_SEARCH_WAIT_MS) {
            if (!_uiState.value.isLoadingSourceStreams && _uiState.value.sourceAllStreams.isNotEmpty()) break
            delay(SOURCE_SEARCH_POLL_MS)
            waited += SOURCE_SEARCH_POLL_MS
            next = nextFallbackCandidate()
        }
        next = next ?: nextFallbackCandidate()
        if (next == null) {
            _uiState.update {
                it.copy(
                    error = context.getString(R.string.player_error_play_stream_failed),
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
            return@launch
        }
        sourceFallbackAttempts++
        next.getStreamUrl()?.let { sourceFallbackTriedUrls += it }
        Log.i(PlayerRuntimeController.TAG, "Source failed to start, trying ${next.addonName}")
        _uiState.update {
            it.copy(loadingMessage = context.getString(R.string.player_source_fallback_trying, next.addonName))
        }
        switchToSourceStream(next)
    }
    return true
}

private fun PlayerRuntimeController.nextFallbackCandidate() =
    _uiState.value.sourceAllStreams.firstOrNull { stream ->
        val url = stream.getStreamUrl()
        // Links still to be resolved (debrid, server, YouTube) have no URL yet; switchToSourceStream resolves them.
        url == null || url !in sourceFallbackTriedUrls
    }?.takeIf { it.getStreamUrl() != currentStreamUrl }

/** A fresh, healthy start clears the fallback budget for the next title. */
internal fun PlayerRuntimeController.resetSourceFallbackState() {
    sourceFallbackAttempts = 0
    sourceFallbackTriedUrls.clear()
    sourceFallbackJob?.cancel()
    sourceFallbackJob = null
}
