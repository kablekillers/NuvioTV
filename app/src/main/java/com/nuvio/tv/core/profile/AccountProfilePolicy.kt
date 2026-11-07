package com.nuvio.tv.core.profile

/**
 * Account-level profile behavior. Never infer this from a profile name or
 * numeric ID: the family and customer accounts both have a Main profile.
 *
 * This policy is deliberately pure; authentication must provide an explicit,
 * persisted account-scoped selection before enabling the new rollout.
 */
internal enum class AccountProfileMode {
    LEGACY_CLOUD,
    FAMILY_CLOUD,
    CUSTOMER_DEVICE_LOCAL
}

internal object AccountProfilePolicy {
    fun usesDeviceOnlySecondaries(mode: AccountProfileMode): Boolean =
        mode == AccountProfileMode.CUSTOMER_DEVICE_LOCAL

    fun maySyncProfileMetadata(profileId: Int, mode: AccountProfileMode): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode) || profileId == 1
    }

    /**
     * Account-wide destructive RPCs must never run for customer-local profiles.
     * Even Main (ID 1) belongs to the same shared login across all customers.
     */
    fun mayDeleteRemoteProfileData(mode: AccountProfileMode): Boolean =
        !usesDeviceOnlySecondaries(mode)

    /**
     * Account-wide PINs and locks are not local profile credentials, even for Main.
     */
    fun maySyncRemoteProfilePins(mode: AccountProfileMode): Boolean =
        !usesDeviceOnlySecondaries(mode)

    fun maySyncViewingState(profileId: Int, mode: AccountProfileMode): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode)
    }

    fun visibleRemoteProfileIds(ids: List<Int>, mode: AccountProfileMode): List<Int> =
        if (usesDeviceOnlySecondaries(mode)) ids.filter { it == 1 } else ids

    /**
     * Main profile ID is NOT an administrator credential: every customer
     * device using the master login has Main (1). Customer-mode writes to
     * shared configuration require a separately verified administrator
     * entitlement. Until that authorization exists, fail closed.
     */
    fun canEditSharedConfiguration(profileId: Int, mode: AccountProfileMode): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode)
    }

    fun canEditSharedConfiguration(
        profileId: Int,
        mode: AccountProfileMode,
        verifiedAdministrator: Boolean
    ): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode) ||
            (profileId == 1 && verifiedAdministrator)
    }
}
