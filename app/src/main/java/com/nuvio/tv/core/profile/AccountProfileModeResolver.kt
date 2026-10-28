package com.nuvio.tv.core.profile

/**
 * Prevents account-specific mode settings from leaking between shared-device
 * logins. A selection belongs to the exact authenticated account identifier.
 *
 * This pure boundary is not yet wired into authentication; rollout remains
 * disabled until the caller verifies account identity and migration safety.
 */
internal object AccountProfileModeResolver {
    fun resolve(
        authenticatedAccountId: String?,
        storedAccountId: String?,
        storedMode: String?,
        migrationEnabled: Boolean
    ): AccountProfileMode {
        val matches = !authenticatedAccountId.isNullOrBlank() &&
            authenticatedAccountId == storedAccountId
        return AccountProfileModeCodec.effectiveMode(
            storedValue = storedMode,
            assignmentVerifiedForCurrentAccount = matches,
            migrationEnabled = migrationEnabled
        )
    }
}
