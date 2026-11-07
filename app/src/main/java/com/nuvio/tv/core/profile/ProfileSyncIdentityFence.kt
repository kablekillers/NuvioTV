package com.nuvio.tv.core.profile

/**
 * Captures authenticated identity and resolved profile mode for suspended
 * personal-data sync work. The caller must also capture and preserve the
 * requested profile ID, and revalidate at every side-effect boundary.
 *
 * This is not an account classifier and never enables customer mode.
 */
internal class ProfileSyncIdentityFence(
    private val currentVerifiedAccountId: () -> String?,
    private val currentMode: () -> AccountProfileMode
) {
    data class Snapshot(val verifiedAccountId: String, val mode: AccountProfileMode)

    private fun validAccountId(id: String?): Boolean =
        !id.isNullOrBlank() && id == id.trim() && id.none { it.isISOControl() }

    fun capture(): Snapshot {
        val id = currentVerifiedAccountId()
        require(validAccountId(id)) {
            "Personal-state sync requires a verified account"
        }
        val mode = currentMode()
        check(currentVerifiedAccountId() == id && currentMode() == mode) {
            "Authenticated account changed while capturing sync context"
        }
        return Snapshot(id, mode)
    }

    fun isCurrent(snapshot: Snapshot): Boolean =
        validAccountId(snapshot.verifiedAccountId) &&
            currentVerifiedAccountId() == snapshot.verifiedAccountId &&
            currentMode() == snapshot.mode

    fun requireCurrent(snapshot: Snapshot) {
        check(isCurrent(snapshot)) {
            "Discarding stale personal-state sync work after account or mode switch"
        }
    }
}
