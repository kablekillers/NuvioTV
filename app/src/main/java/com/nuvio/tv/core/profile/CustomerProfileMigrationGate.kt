package com.nuvio.tv.core.profile

/**
 * Preflight for a customer account migration. Never delete remote profiles or
 * rewrite local profile IDs implicitly. A collision blocks activation until
 * a separate, explicitly tested remapping step is available.
 */
internal object CustomerProfileMigrationGate {
    data class Decision(
        val mayActivate: Boolean,
        val reason: String
    )

    fun evaluate(
        mode: AccountProfileMode,
        assignmentVerified: Boolean,
        migrationApproved: Boolean,
        syncAuditComplete: Boolean,
        existingCloudProfileIds: List<Int>,
        existingDeviceProfileIds: List<Int>,
        mainProfilePresent: Boolean = true,
        localProfileCountLimit: Int = 5,
        migrationStatePersisted: Boolean = false,
        personalStateIsolationVerified: Boolean = false,
        sharedMainLayoutVerified: Boolean = false,
        cloudProfileDeletionDisabled: Boolean = false,
        historicalCloudProfilesPreserved: Boolean = false
    ): Decision {
        if (mode != AccountProfileMode.CUSTOMER_DEVICE_LOCAL) {
            return Decision(false, "Customer migration applies only to customer accounts")
        }
        if (!assignmentVerified) return Decision(false, "Account identity is not verified")
        if (!migrationApproved) return Decision(false, "Migration has not been approved")
        if (!migrationStatePersisted) return Decision(false, "Migration state has not been durably recorded")
        if (!syncAuditComplete) return Decision(false, "Personal-data sync audit is incomplete")
        if (!personalStateIsolationVerified) {
            return Decision(false, "Personal viewing data isolation is not verified")
        }
        if (!sharedMainLayoutVerified) {
            return Decision(false, "Main layout propagation is not verified")
        }
        if (!cloudProfileDeletionDisabled) {
            return Decision(false, "Legacy cloud profile deletion must be disabled")
        }
        if (!mainProfilePresent || 1 !in existingDeviceProfileIds || 1 !in existingCloudProfileIds) {
            return Decision(false, "Main profile must be present in both local and cloud state")
        }
        if (localProfileCountLimit !in 1..5) {
            return Decision(false, "Customer profile limit must be between one and five")
        }
        if (existingDeviceProfileIds.size != existingDeviceProfileIds.distinct().size ||
            existingCloudProfileIds.size != existingCloudProfileIds.distinct().size
        ) {
            return Decision(false, "Duplicate profile IDs require data repair before migration")
        }
        if (existingDeviceProfileIds.distinct().size > localProfileCountLimit + 1) {
            return Decision(false, "Local profile count exceeds the customer profile limit")
        }
        if (existingCloudProfileIds.any { it <= 0 } || existingDeviceProfileIds.any { it <= 0 }) {
            return Decision(false, "Invalid profile IDs require data repair before migration")
        }
        // Cloud-only secondaries may still contain historical customer viewing data.
        if (!historicalCloudProfilesPreserved) {
            return Decision(false, "Historical cloud profiles have not been preserved")
        }
        val plan = DeviceProfileMigrationPlanner.inspect(existingCloudProfileIds, existingDeviceProfileIds)
        if (plan.requiresLocalIdRemapping) {
            return Decision(false, "Legacy cloud and device profile IDs conflict; remapping required")
        }
        return Decision(true, "Migration preflight passed")
    }
}
