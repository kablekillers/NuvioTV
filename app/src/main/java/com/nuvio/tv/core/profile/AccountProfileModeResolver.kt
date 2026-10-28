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
            authenticatedAccountId == authenticatedAccountId.trim() &&
            !storedAccountId.isNullOrBlank() &&
            storedAccountId == storedAccountId.trim() &&
            authenticatedAccountId == storedAccountId
        // A mode assignment for a different login must not leak across accounts,
        // including an explicitly selected family-cloud assignment.
        if (!matches) return AccountProfileMode.LEGACY_CLOUD
        return AccountProfileModeCodec.effectiveMode(
            storedValue = storedMode,
            assignmentVerifiedForCurrentAccount = true,
            migrationEnabled = migrationEnabled
        )
    }
}
