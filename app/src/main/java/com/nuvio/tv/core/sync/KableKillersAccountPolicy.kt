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
        householdLocks: Map<Int, Boolean> = emptyMap()
    ): Map<Int, Boolean> {
        if (!isCustomerAccount(authState)) return cloudLocks
        val primaryLocked = cloudLocks[PRIMARY_PROFILE_ID] == true || !cloudLocksConfirmed
        return householdLocks.filterKeys { it != PRIMARY_PROFILE_ID } + (PRIMARY_PROFILE_ID to primaryLocked)
    }

    /**
     * Continue Watching, watch progress, watched marks and Library.
     *
     * On the shared customer login only Profile 1 uses the cloud. Household
     * profiles exist only on the device they were made on, so the cloud has no
     * matching profile for them; syncing them by number would show Profile 1's
     * (or another customer's) history. They stay on this device instead.
     * Every profile on any other login syncs normally.
     */
    fun syncsWatchDataToCloud(authState: AuthState, profileId: Int): Boolean =
        profileId == PRIMARY_PROFILE_ID || cloudProfilesEnabled(authState)

    /** PINs for this profile are saved on the device instead of the cloud. */
    fun usesDevicePin(authState: AuthState, profileId: Int): Boolean =
        profileId != PRIMARY_PROFILE_ID && isCustomerAccount(authState)
}
