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
        existingDeviceProfileIds: List<Int>
    ): Decision {
        if (mode != AccountProfileMode.CUSTOMER_DEVICE_LOCAL) {
            return Decision(false, "Customer migration applies only to customer accounts")
        }
        if (!assignmentVerified) return Decision(false, "Account identity is not verified")
        if (!migrationApproved) return Decision(false, "Migration has not been approved")
        if (!syncAuditComplete) return Decision(false, "Personal-data sync audit is incomplete")
        val plan = DeviceProfileMigrationPlanner.inspect(existingCloudProfileIds, existingDeviceProfileIds)
        if (plan.requiresLocalIdRemapping) {
            return Decision(false, "Legacy cloud and device profile IDs conflict; remapping required")
        }
        return Decision(true, "Migration preflight passed")
    }
}
