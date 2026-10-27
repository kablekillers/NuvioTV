package com.nuvio.tv.core.profile

/**
 * Separates account-wide addon configuration from device-local viewing state.
 *
 * This policy does not persist profiles or alter existing synchronization yet.
 * Repositories must apply these scopes at every read/write boundary.
 */
sealed interface ProfileDataScope {
    data object SharedAccount : ProfileDataScope
    data class LocalProfile(val profileId: String) : ProfileDataScope {
        init {
            require(profileId.isNotBlank()) { "Local profile ID must not be blank" }
        }
    }
}

enum class ProfileDataKind {
    ADDONS,
    PLUGINS,
    ADDON_SETTINGS,
    CATALOG_CONFIGURATION,
    CONTINUE_WATCHING,
    WATCH_HISTORY,
    FAVORITES,
    PLAYBACK_PROGRESS
}

object ProfileDataPolicy {
    fun scopeFor(kind: ProfileDataKind, localProfileId: String?): ProfileDataScope =
        when (kind) {
            ProfileDataKind.ADDONS,
            ProfileDataKind.PLUGINS,
            ProfileDataKind.ADDON_SETTINGS,
            ProfileDataKind.CATALOG_CONFIGURATION -> ProfileDataScope.SharedAccount

            ProfileDataKind.CONTINUE_WATCHING,
            ProfileDataKind.WATCH_HISTORY,
            ProfileDataKind.FAVORITES,
            ProfileDataKind.PLAYBACK_PROGRESS ->
                if (localProfileId == null) ProfileDataScope.SharedAccount
                else ProfileDataScope.LocalProfile(localProfileId)
        }

    /** Personal local viewing state must never be uploaded to the shared account. */
    fun canUploadToSharedAccount(kind: ProfileDataKind, localProfileId: String?): Boolean =
        scopeFor(kind, localProfileId) == ProfileDataScope.SharedAccount
}
