package com.nuvio.tv.core.profile

import java.io.File

/**
 * Read-only preflight for a future transactional migration.
 * This deliberately does not copy, delete, remap or activate any profiles.
 */
internal object ProfileMigrationCoordinator {
    data class Preflight(
        val readyForManualMigration: Boolean,
        val reason: String,
        val proposedRemaps: Map<Int, Int> = emptyMap()
    )

    fun preflight(
        directory: File,
        verifiedAccountId: String,
        cloudIds: List<Int>,
        localIds: List<Int>
    ): Preflight {
        if (verifiedAccountId.isBlank() || verifiedAccountId != verifiedAccountId.trim() ||
            verifiedAccountId.any { it.isISOControl() }) {
            return Preflight(false, "Verified account ID is invalid")
        }
        if (!directory.isDirectory || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            return Preflight(false, "Migration storage directory is unavailable")
        }
        if (runCatching {
                ProfileMigrationJournal.hasUnresolvedMigration(directory, verifiedAccountId)
            }.getOrDefault(true)) {
            return Preflight(false, "Unresolved or unreadable migration journal")
        }
        return runCatching {
            val remaps = DeviceProfileMigrationPlanner.proposeLocalRemap(cloudIds, localIds)
            Preflight(true, "Read-only migration preflight passed", remaps)
        }.getOrElse {
            Preflight(false, "Profile IDs cannot be migrated safely")
        }
    }
}
