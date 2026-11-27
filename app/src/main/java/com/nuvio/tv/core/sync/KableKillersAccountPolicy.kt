package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.AuthState

/**
 * Kable Killers profile storage rules.
 *
 * - The shared customer login (kablekillers6@gmail.com) keeps household profiles
 *   on the device they were made on. Only Profile 1 talks to the cloud.
 * - Any other signed-in account (for example the owner's personal login) keeps
 *   every profile in the cloud so all devices on that login see the same list.
 *
 * Anything uncertain (signed out, auth still loading, blank email) is treated as
 * the customer account, so a customer device can never upload its local profiles.
 */
object KableKillersAccountPolicy {
    const val SHARED_CUSTOMER_EMAIL = "kablekillers6@gmail.com"

    fun cloudProfilesEnabled(authState: AuthState): Boolean {
        val account = authState as? AuthState.FullAccount ?: return false
        val email = account.email.trim()
        if (email.isEmpty()) return false
        return !email.equals(SHARED_CUSTOMER_EMAIL, ignoreCase = true)
    }

    const val PRIMARY_PROFILE_ID = 1

    /** Signed in to the shared customer login (not signed out, not the owner's login). */
    fun isCustomerAccount(authState: AuthState): Boolean =
        authState is AuthState.FullAccount && !cloudProfilesEnabled(authState)

    /**
     * The PIN locks the app should actually enforce.
     *
     * On the shared customer login:
     * - Profile 1 uses the cloud PIN, and counts as locked until this device has
     *   confirmed its PIN state with the cloud (fail closed: no network never
     *   means no PIN).
     * - Household profiles ignore cloud PINs and use PINs saved on this device
     *   ([householdLocks]). The cloud stores PINs by profile number for the whole
     *   login, so a cloud PIN on one customer's "Profile 2" would lock every
     *   customer's Profile 2; a device PIN locks only this TV.
     */
    fun enforcedPinLocks(
        authState: AuthState,
        cloudLocks: Map<Int, Boolean>,
        cloudLocksConfirmed: Boolean,
        householdLocks: Map<Int, Boolean> = emptyMap(),
        backupOnlyCustomer: Boolean = false
    ): Map<Int, Boolean> {
        // A device signed in to the customer login only through the Kable Killers backup
        // (Nuvio was down) follows the customer rules too, with Profile 1 always locked:
        // it opens with the owner's restore PIN.
        if (backupOnlyCustomer) {
            return householdLocks.filterKeys { it != PRIMARY_PROFILE_ID } + (PRIMARY_PROFILE_ID to true)
        }
        if (!isCustomerAccount(authState)) return cloudLocks
        val primaryLocked = cloudLocks[PRIMARY_PROFILE_ID] == true || !cloudLocksConfirmed
        return householdLocks.filterKeys { it != PRIMARY_PROFILE_ID } + (PRIMARY_PROFILE_ID to primaryLocked)
    }

    /**
     * Continue Watching, watch progress, watched marks and Library.
     *
     * On the shared customer login every profile keeps these on the device, Profile 1
     * included: Profile 1 is the same cloud profile in every customer's house, so syncing
     * its history showed one house's shows in everyone's Continue Watching. Profile 1's
     * setup (add-ons, rows, hidden rows, settings) still comes from the cloud.
     * Every profile on any other login (the owner's) syncs normally.
     */
    @Suppress("UNUSED_PARAMETER")
    fun syncsWatchDataToCloud(authState: AuthState, profileId: Int): Boolean =
        cloudProfilesEnabled(authState)

    /** PINs for this profile are saved on the device instead of the cloud. */
    fun usesDevicePin(authState: AuthState, profileId: Int, backupOnlyCustomer: Boolean = false): Boolean =
        profileId != PRIMARY_PROFILE_ID && (isCustomerAccount(authState) || backupOnlyCustomer)
}
