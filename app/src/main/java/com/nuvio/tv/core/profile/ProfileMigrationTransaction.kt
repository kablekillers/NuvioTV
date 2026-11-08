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
        require(java.nio.file.Files.isRegularFile(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Migration source must be a regular file"
        }
        require(!java.nio.file.Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Migration destination must be absent before journaling"
        }
        require(java.nio.file.Files.isDirectory(destination.toPath().parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Migration destination parent must be an existing directory"
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
