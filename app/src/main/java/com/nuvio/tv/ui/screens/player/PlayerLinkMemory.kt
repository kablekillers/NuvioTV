package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.links.LinkMemory

/** Notes when the playing link has really played (20 seconds), or failed, once per link. */
private object PlayerLinkMemoryTracker {
    var key: String? = null
    var playingSince: Long = 0L
    var done = false
}

internal fun PlayerRuntimeController.trackLinkMemory() {
    val state = _uiState.value
    val tracker = PlayerLinkMemoryTracker
    val identity = currentStreamUrl
    if (tracker.key != identity) {
        tracker.key = identity
        tracker.playingSince = 0L
        tracker.done = false
    }
    if (tracker.done) return
    val addon = currentAddonName
    val streamLabel = state.currentStreamName
    if (state.error != null) {
        // Only links that never got going count as broken.
        tracker.done = true
        LinkMemory.recordFailure(contentId, title, addon, streamLabel)
        return
    }
    if (!state.isPlaying || state.isBuffering) {
        tracker.playingSince = 0L
        return
    }
    val now = System.currentTimeMillis()
    if (tracker.playingSince == 0L) tracker.playingSince = now
    if (now - tracker.playingSince >= 20_000L) {
        tracker.done = true
        LinkMemory.recordSuccess(contentId, title, addon, streamLabel)
    }
}
