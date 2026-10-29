package com.nuvio.tv.core.profile

/**
 * Single authoritative source for home-screen layout in device-local profile mode.
 * The main profile is always ID 1. Legacy mode preserves per-profile settings.
 *
 * Do not enable device-local mode before profile migration, catalog sync and
 * personal watch-state isolation are wired together.
 */
internal object SharedLayoutScope {
    const val MAIN_PROFILE_ID = 1

    fun configurationProfileId(activeProfileId: Int, deviceLocalModeEnabled: Boolean): Int {
        require(activeProfileId > 0) { "Profile ID must be positive" }
        return if (deviceLocalModeEnabled) MAIN_PROFILE_ID else activeProfileId
    }

    fun canEditConfiguration(activeProfileId: Int, deviceLocalModeEnabled: Boolean): Boolean =
        !deviceLocalModeEnabled || activeProfileId == MAIN_PROFILE_ID
}
