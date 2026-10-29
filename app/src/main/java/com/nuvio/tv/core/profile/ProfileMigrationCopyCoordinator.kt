package com.nuvio.tv.core.profile

import java.io.File

/**
 * Opt-in, copy-only migration stage. This is not called by account activation.
 * The caller must first stop all DataStore writers, validate account ownership,
 * and obtain an exclusive migration lock.
 *
 * A failed or interrupted stage leaves its journal unresolved. Never remove
 * destinations from a prior attempt automatically: ownership cannot be proven.
 */
internal object ProfileMigrationCopyCoordinator {
    fun copyWithJournal(
        directory: File,
        accountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        manifest: Map<String, String>
    ): ProfileFileCopyTransaction.Result {
        require(!ProfileMigrationJournal.hasUnresolvedMigration(directory)) {
            "An earlier migration requires recovery"
        }
        require(ProfileMigrationJournal.read(directory) == null) {
            "A previous migration journal requires explicit reconciliation"
        }
        require(manifest.isNotEmpty()) { "Empty profile migration manifest" }
        val expectedSourceSuffix = "_p$sourceProfileId.preferences_pb"
        val expectedTargetSuffix = "_p$targetProfileId.preferences_pb"
        require(manifest.all { (source, target) ->
            source.endsWith(expectedSourceSuffix) &&
                target == source.removeSuffix(expectedSourceSuffix) + expectedTargetSuffix
        }) {
            "Profile migration manifest does not match the verified source and target IDs"
        }
        val entry = ProfileMigrationJournal.Entry(
            accountId, sourceProfileId, targetProfileId,
            ProfileMigrationJournal.Phase.PREPARED
        )
        ProfileMigrationJournal.write(directory, entry)
        ProfileMigrationJournal.write(
            directory, entry.copy(phase = ProfileMigrationJournal.Phase.COPYING)
        )
        val result = ProfileFileCopyTransaction.copy(directory, manifest)
        ProfileMigrationJournal.write(
            directory, entry.copy(phase = ProfileMigrationJournal.Phase.VERIFIED)
        )
        // COMPLETED is intentionally reserved for a future audited metadata
        // remap and profile-state reconciliation. Do not mark it here.
        return result
    }
}
