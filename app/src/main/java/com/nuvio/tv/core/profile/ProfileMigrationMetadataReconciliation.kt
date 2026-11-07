package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile

/**
 * Read-only comparison of a migration's intended metadata state against
 * the state read back from the profile DataStore.
 *
 * This cannot by itself complete migration: file integrity, journal ownership,
 * app writers and other profile-scoped stores must still be audited.
 */
internal object ProfileMigrationMetadataReconciliation {
    fun matches(
        originalProfiles: List<UserProfile>,
        originalActiveId: Int,
        sourceId: Int,
        targetId: Int,
        persistedProfiles: List<UserProfile>,
        persistedActiveId: Int
    ): Boolean = runCatching {
        val expected = DeviceProfileMetadataRemapPlanner.propose(
            originalProfiles, originalActiveId, mapOf(sourceId to targetId)
        )
        persistedProfiles == expected.profiles &&
            persistedActiveId == expected.activeProfileId &&
            persistedProfiles.none { it.id == sourceId } &&
            persistedProfiles.count { it.id == targetId } == 1
    }.getOrDefault(false)
}
