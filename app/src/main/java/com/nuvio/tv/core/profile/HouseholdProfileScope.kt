package com.nuvio.tv.core.profile

/**
 * Reserved for possible future household sync. Current customer rollout uses device-local profiles instead. The shared login is NOT a household ID.
 *
 * A household must be explicitly enrolled with a server-authorized membership.
 * Never derive household identity from account email, device name, IP address,
 * profile index or an unverified client-supplied value.
 *
 * This model must not be wired into current customer sync: customer profile rollout remains disabled
 * until server-side authorization and migration are implemented and tested.
 */
internal data class HouseholdProfileScope(
    val authenticatedAccountId: String,
    val householdId: String,
    val profileId: String
) {
    init {
        require(authenticatedAccountId.isNotBlank())
        require(householdId.isNotBlank())
        require(profileId.isNotBlank())
    }
}

internal enum class HouseholdSyncResource {
    PROFILE_METADATA,
    WATCH_HISTORY,
    WATCH_PROGRESS,
    COLLECTIONS,
    LIBRARY
}

/**
 * Pure boundary policy. Cloud RPCs must additionally enforce the same scope
 * server-side using verified membership; this check alone is not authorization.
 */
internal object HouseholdProfileScopePolicy {
    fun mayApplyPersonalData(
        requested: HouseholdProfileScope,
        response: HouseholdProfileScope
    ): Boolean = requested == response

    fun mayShareMasterConfiguration(
        requestingAccountId: String,
        masterAccountId: String
    ): Boolean = requestingAccountId.isNotBlank() &&
        requestingAccountId == masterAccountId
}
