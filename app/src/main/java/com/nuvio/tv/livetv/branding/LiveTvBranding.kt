package com.nuvio.tv.livetv.branding

import com.nuvio.tv.R

/** Kable Killers branding for the Live TV section (ported from Nuvio One). */
object LiveTvBranding {
    const val APP_NAME = "Kable Killers"
    const val TAGLINE = "All your entertainment. One app."

    /** The Kable Killers wordmark, whatever the theme. */
    fun iptvWordmark(nuvioWordmark: Int = 0): Int = R.drawable.app_logo_wordmark
}
