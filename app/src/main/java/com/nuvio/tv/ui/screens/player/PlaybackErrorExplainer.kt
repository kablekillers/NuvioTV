package com.nuvio.tv.ui.screens.player

/**
 * Where a playback failure most likely comes from, so the error screen can say it in plain words:
 * the source (link dead, expired or blocked), the connection, or this device (format it can't play).
 */
enum class PlaybackErrorCause { SOURCE, CONNECTION, DEVICE, UNKNOWN }

object PlaybackErrorExplainer {
    private val sourceHints = listOf(
        "403", "404", "410", "451", "429", "500", "502", "503", "504",
        "forbidden", "not found", "gone", "unauthorized", "expired", "invalid response code",
        "response code", "http error", "manifest", "malformed", "parser", "no playable", "playlist",
        "unable to connect to source", "source", "torrent", "no seeders", "debrid", "link",
    )
    private val connectionHints = listOf(
        "timeout", "timed out", "unknownhost", "unknown host", "unable to resolve", "no address associated",
        "network", "connection reset", "connection refused", "connect failed", "econn", "socket",
        "ssl", "handshake", "certificate", "offline", "no internet", "behind live window", "unreachable",
    )
    private val deviceHints = listOf(
        "decoder", "codec", "mediacodec", "renderer", "unsupported", "not supported", "format_unsupported",
        "exceeds_capabilities", "drm", "widevine", "audio track", "audiosink", "surface", "hdr", "dolby vision",
        "out of memory", "oom",
    )

    fun classify(message: String?): PlaybackErrorCause {
        val text = message?.lowercase()?.takeIf { it.isNotBlank() } ?: return PlaybackErrorCause.UNKNOWN
        // Device problems first: a decoder error can mention the source URL too.
        if (deviceHints.any { it in text }) return PlaybackErrorCause.DEVICE
        if (connectionHints.any { it in text }) return PlaybackErrorCause.CONNECTION
        if (sourceHints.any { it in text }) return PlaybackErrorCause.SOURCE
        return PlaybackErrorCause.UNKNOWN
    }
}
