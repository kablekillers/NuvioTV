package com.nuvio.tv.youtube

import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * Nuvio One's YouTube feed list (from its YouTube screen), kept here because the
 * YouTube settings Kable Killers Music shares refer to it.
 */
enum class YtTab(
    /** The English label the YouTube engine expects for this feed. */
    val title: String,
    @StringRes val titleRes: Int,
    val needsSignIn: Boolean = false,
    val grid: Boolean = false
) {
    HOME("Home", R.string.onetube_tab_home),
    SUBSCRIPTIONS("Subscriptions", R.string.onetube_tab_subscriptions, needsSignIn = true, grid = true),
    HISTORY("History", R.string.onetube_tab_history, needsSignIn = true, grid = true),
    PLAYLISTS("Playlists", R.string.onetube_tab_playlists, needsSignIn = true, grid = true),
    LIVE("Live", R.string.onetube_tab_live),
    MUSIC("Music", R.string.onetube_tab_music),
    GAMING("Gaming", R.string.onetube_tab_gaming),
    NEWS("News", R.string.onetube_tab_news),
    SPORTS("Sports", R.string.onetube_tab_sports),
    SHORTS("Shorts", R.string.onetube_tab_shorts, grid = true),
    CHANNELS("Channels", R.string.onetube_tab_channels, needsSignIn = true, grid = true)
}
