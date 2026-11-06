package com.nuvio.tv.data.local

/**
 * An in-process barrier against opening or reusing profile-scoped DataStores
 * while an explicit profile-ID migration is in progress.
 *
 * Callers must coordinate this gate with the factory cache under the SAME
 * monitor. This is not a cross-process lock and does not invalidate stores
 * already handed out to callers. It is not wired to account activation.
 */
internal class ProfileRemapStoreAccessGate {
    private var blockedIds: Set<Int> = emptySet()

    fun begin(sourceId: Int, targetId: Int, cachedIds: Set<Int>) {
        require(sourceId in 2..6 && targetId in 2..6 && sourceId != targetId) {
            "Unsupported profile remap"
        }
        check(blockedIds.isEmpty()) { "Another profile remap access barrier is active" }
        check(sourceId !in cachedIds && targetId !in cachedIds) {
            "Source or destination DataStore is still cached"
        }
        blockedIds = setOf(sourceId, targetId)
    }

    fun checkAccess(profileId: Int) {
        check(profileId !in blockedIds) {
            "Profile DataStore access is blocked during migration"
        }
    }

    fun end(sourceId: Int, targetId: Int) {
        check(blockedIds == setOf(sourceId, targetId)) {
            "Only the active profile remap may release its access barrier"
        }
        blockedIds = emptySet()
    }

    fun isActive(): Boolean = blockedIds.isNotEmpty()
}
