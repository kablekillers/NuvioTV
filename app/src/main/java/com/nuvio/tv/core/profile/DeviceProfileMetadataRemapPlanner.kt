package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile

/**
 * Pure, non-mutating preparation for the future device-local migration.
 * Never writes profile metadata, deletes cloud records, or starts DataStores.
 *
 * File copying, journal reconciliation and metadata persistence must be
 * coordinated by a separate recovery-safe transaction before activation.
 */
internal object DeviceProfileMetadataRemapPlanner {
    data class Proposal(
        val profiles: List<UserProfile>,
        val activeProfileId: Int
    )

    fun propose(
        existingProfiles: List<UserProfile>,
        activeProfileId: Int,
        localIdRemap: Map<Int, Int>
    ): Proposal {
        require(existingProfiles.isNotEmpty() && existingProfiles.size <= 6) {
            "Expected Main and no more than five local profiles"
        }
        val existingIds = existingProfiles.map { it.id }
        require(existingIds.size == existingIds.distinct().size && existingIds.all { it in 1..6 }) {
            "Duplicate or unsupported local profile identifiers"
        }
        require(1 in existingIds && activeProfileId in existingIds) {
            "Main and the active profile must exist"
        }
        require(localIdRemap.keys.all { it in existingIds && it != 1 }) {
            "Only existing secondary profiles can be remapped"
        }
        require(localIdRemap.values.all { it in 2..6 } &&
            localIdRemap.values.size == localIdRemap.values.toSet().size) {
            "Migration targets must be unique supported secondary IDs"
        }
        require(localIdRemap.values.none { it in existingIds }) {
            "Migration must not overwrite an existing local profile"
        }

        val proposedProfiles = existingProfiles.map { profile ->
            val newId = localIdRemap[profile.id] ?: profile.id
            if (profile.id == 1) profile else profile.copy(
                id = newId,
                usesPrimaryAddons = true,
                usesPrimaryPlugins = true
            )
        }
        check(proposedProfiles.map { it.id }.distinct().size == existingProfiles.size) {
            "Remapped profiles collide"
        }
        return Proposal(
            profiles = proposedProfiles,
            activeProfileId = localIdRemap[activeProfileId] ?: activeProfileId
        )
    }
}
