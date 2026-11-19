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
}
