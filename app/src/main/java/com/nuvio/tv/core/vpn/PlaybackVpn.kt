package com.nuvio.tv.core.vpn

/**
 * Nuvio One routes Live TV playback through its VPN here. Kable Killers has no VPN,
 * so the player's own HTTP factory is used as is.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun protectHttpFactory(
    upstream: androidx.media3.datasource.HttpDataSource.Factory,
    headers: Map<String, String>,
    userAgent: String,
): androidx.media3.datasource.HttpDataSource.Factory = upstream
