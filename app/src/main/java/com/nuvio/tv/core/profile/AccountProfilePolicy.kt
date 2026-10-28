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

    fun maySyncViewingState(profileId: Int, mode: AccountProfileMode): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode) || profileId == 1
    }

    fun visibleRemoteProfileIds(ids: List<Int>, mode: AccountProfileMode): List<Int> =
        if (usesDeviceOnlySecondaries(mode)) ids.filter { it == 1 } else ids

    fun canEditSharedConfiguration(profileId: Int, mode: AccountProfileMode): Boolean {
        require(profileId > 0)
        return !usesDeviceOnlySecondaries(mode) || profileId == 1
    }
}
