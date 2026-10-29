package com.nuvio.tv.core.profile

/**
 * Prevents account-specific mode settings from leaking between shared-device
 * logins. A selection belongs to the exact authenticated account identifier.
 *
 * The authenticated provider uses this boundary; rollout remains disabled
 * until account-specific migration and synchronization are verified.
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
