package com.nuvio.tv.core.profile

/**
 * Persisted account policy must be tied to the authenticated account ID, not
 * the device or profile name. Unrecognized or missing values fail closed to
 * the existing cloud-sync behavior; they must never activate device-local mode.
 */
internal object AccountProfileModeCodec {
    fun encode(mode: AccountProfileMode): String = when (mode) {
        AccountProfileMode.LEGACY_CLOUD -> "legacy_cloud"
        AccountProfileMode.FAMILY_CLOUD -> "family_cloud"
        AccountProfileMode.CUSTOMER_DEVICE_LOCAL -> "customer_device_local"
    }

    fun decode(value: String?): AccountProfileMode = when (value) {
        "family_cloud" -> AccountProfileMode.FAMILY_CLOUD
        "customer_device_local" -> AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        else -> AccountProfileMode.LEGACY_CLOUD
    }

    /**
     * Customer mode requires a deliberate, verified per-account assignment
     * AND completion of the migration rollout. A cached mode string alone
     * cannot enable a destructive or privacy-sensitive sync transition.
     */
    fun effectiveMode(
        storedValue: String?,
        assignmentVerifiedForCurrentAccount: Boolean,
        migrationEnabled: Boolean
    ): AccountProfileMode {
        if (!assignmentVerifiedForCurrentAccount) return AccountProfileMode.LEGACY_CLOUD
        val requested = decode(storedValue)
        return if (requested == AccountProfileMode.CUSTOMER_DEVICE_LOCAL &&
            !migrationEnabled
        ) AccountProfileMode.LEGACY_CLOUD else requested
    }
}
