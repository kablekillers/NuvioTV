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
     * - Only Profile 1 can be PIN-locked. Household profiles live on one device
     *   but PINs are stored in the cloud by profile number, so a PIN on one
     *   customer's "Profile 2" would lock every other customer's Profile 2.
     * - Profile 1 counts as locked until this device has confirmed its PIN
     *   state with the cloud (fail closed: no network never means no PIN).
     */
    fun enforcedPinLocks(
        authState: AuthState,
        cloudLocks: Map<Int, Boolean>,
        cloudLocksConfirmed: Boolean
    ): Map<Int, Boolean> {
        if (!isCustomerAccount(authState)) return cloudLocks
        val primaryLocked = cloudLocks[PRIMARY_PROFILE_ID] == true || !cloudLocksConfirmed
        return mapOf(PRIMARY_PROFILE_ID to primaryLocked)
    }
}
