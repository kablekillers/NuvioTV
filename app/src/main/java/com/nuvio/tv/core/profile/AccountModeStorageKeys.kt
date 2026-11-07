package com.nuvio.tv.core.profile

import java.security.MessageDigest

/** Account-specific keys prevent assignments being reused across logins. */
internal object AccountModeStorageKeys {
    data class Keys(
        val verifiedId: String,
        val mode: String,
        val migrationComplete: String
    )

    fun forVerifiedAccount(accountId: String): Keys {
        require(accountId.isNotBlank() && accountId == accountId.trim()) {
            "A verified account ID is required"
        }
        val namespace = MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Keys(
            verifiedId = "account_${namespace}_verified_id",
            mode = "account_${namespace}_mode",
            migrationComplete = "account_${namespace}_migration_complete"
        )
    }
}
