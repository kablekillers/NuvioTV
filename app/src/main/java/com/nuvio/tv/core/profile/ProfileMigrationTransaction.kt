package com.nuvio.tv.core.profile

import java.io.File

/**
 * Explicit, single-file migration transaction for development testing only.
 * Not wired to profile activation. A failed copy leaves the journal unresolved
 * and never deletes the source or destination for automatic recovery.
 */
internal object ProfileMigrationTransaction {
    fun copyWithJournal(
        directory: File,
        verifiedAccountId: String,
        sourceId: Int,
        targetId: Int,
        source: File,
        destination: File
    ): Boolean {
        val prepared = ProfileMigrationJournal.Entry(
            verifiedAccountId, sourceId, targetId, ProfileMigrationJournal.Phase.PREPARED
        )
        require(!ProfileMigrationJournal.hasUnresolvedMigration(directory)) {
            "An unresolved migration must be reconciled before starting another"
        }
        require(ProfileMigrationJournal.read(directory) == null) {
            "Existing migration journal requires explicit reconciliation"
        }
        ProfileMigrationJournal.write(directory, prepared)
        ProfileMigrationJournal.write(
            directory, prepared.copy(phase = ProfileMigrationJournal.Phase.COPYING)
        )
        if (!ProfileMigrationCopyVerifier.copyAndVerify(source, destination)) return false
        ProfileMigrationJournal.write(
            directory, prepared.copy(phase = ProfileMigrationJournal.Phase.VERIFIED)
        )
        // COMPLETED is intentionally not written here: a verified single-file
        // copy cannot prove that all profile-scoped stores were migrated.
        return true
    }
}
