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
    /**
     * Read-only recovery assessment. Never deletes copied files or advances
     * journal state. A verified copy still requires metadata reconciliation.
     */
    data class RecoveryAssessment(
        val phase: ProfileMigrationJournal.Phase?,
        val requiresManualReconciliation: Boolean,
        val accountMatches: Boolean
    )

    fun assessRecovery(directory: File, verifiedAccountId: String): RecoveryAssessment {
        // Invalid or partial journals must never be interpreted as a clean
        // migration. Report a blocked, unknown state without mutating files.
        val unresolved = runCatching {
            ProfileMigrationJournal.hasUnresolvedMigration(directory)
        }.getOrElse {
            return RecoveryAssessment(null, true, false)
        }
        val journal = runCatching {
            ProfileMigrationJournal.read(directory)
        }.getOrElse {
            return RecoveryAssessment(null, true, false)
        }
        return RecoveryAssessment(
            phase = journal?.phase,
            requiresManualReconciliation = unresolved || journal != null,
            accountMatches = journal != null &&
                verifiedAccountId.isNotBlank() &&
                verifiedAccountId == verifiedAccountId.trim() &&
                journal.accountId == verifiedAccountId
        )
    }

    fun copyWithJournal(
        directory: File,
        accountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        manifest: Map<String, String>
    ): ProfileFileCopyTransaction.Result {
        // Lock covers preflight, journal creation, file copies and VERIFIED.
        // No second migration may observe a partial stage as a clean start.
        return ProfileMigrationExclusiveLock.tryWithLock(directory) {
            copyWithJournalLocked(
                directory, accountId, sourceProfileId, targetProfileId, manifest
            )
        } ?: error("Another profile migration already holds the storage lock")
    }

    private fun copyWithJournalLocked(
        directory: File,
        accountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        manifest: Map<String, String>
    ): ProfileFileCopyTransaction.Result {
        require(sourceProfileId in 2..6 && targetProfileId in 2..6 &&
            sourceProfileId != targetProfileId) {
            "Only supported secondary profile IDs can be migrated"
        }
        require(!ProfileMigrationJournal.hasUnresolvedMigration(directory)) {
            "An earlier migration requires recovery"
        }
        require(ProfileMigrationJournal.read(directory) == null) {
            "A previous migration journal requires explicit reconciliation"
        }
        require(manifest.isNotEmpty()) { "Empty profile migration manifest" }
        require(manifest.values.size == manifest.values.toSet().size) {
            "Duplicate profile migration destinations"
        }
        require(manifest.keys.intersect(manifest.values.toSet()).isEmpty()) {
            "Profile migration sources and destinations must not overlap"
        }
        val expectedSourceSuffix = "_p$sourceProfileId.preferences_pb"
        val expectedTargetSuffix = "_p$targetProfileId.preferences_pb"
        require(manifest.all { (source, target) ->
            source.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb")) &&
                target.matches(Regex("[A-Za-z0-9_-]+\\.preferences_pb")) &&
                source.endsWith(expectedSourceSuffix) &&
                target == source.removeSuffix(expectedSourceSuffix) + expectedTargetSuffix
        }) {
            "Profile migration manifest does not match the verified source and target IDs"
        }
        // An incomplete manifest can silently strand history, progress or
        // favorites after profile metadata is remapped. Reject it BEFORE
        // committing the journal; recovery also independently checks this.
        val sourceSuffix = "_p$sourceProfileId.preferences_pb"
        val existingSources = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(sourceSuffix) }
            ?.map { it.name }?.toSet()
            ?: error("Cannot inventory profile data files")
        require(existingSources == manifest.keys) {
            "Migration manifest does not include every source profile store"
        }
        // Shadow backups are used for corruption recovery. Until backup files
        // are included in an audited migration transaction, moving only the
        // primary DataStore files risks silently stranding recovery state.
        val sourceBackupSuffix = "_p$sourceProfileId.preferences_pb.bak"
        val targetBackupSuffix = "_p$targetProfileId.preferences_pb.bak"
        require(directory.listFiles()?.none { file ->
            file.name.endsWith(sourceBackupSuffix) ||
                file.name.endsWith(targetBackupSuffix)
        } == true) {
            "Profile migration cannot omit source or target shadow backups"
        }
        // Reject obvious missing sources and occupied targets before creating a
        // durable journal. The copy primitive repeats its stronger checksum
        // and CREATE_NEW checks after journaling to cover concurrent changes.
        require(manifest.all { (source, target) ->
            val sourceFile = File(directory, source)
            val targetFile = File(directory, target)
            sourceFile.isFile && sourceFile.length() > 0L &&
                !targetFile.exists() &&
                !java.nio.file.Files.isSymbolicLink(sourceFile.toPath()) &&
                !java.nio.file.Files.isSymbolicLink(targetFile.toPath())
        }) { "Missing source, unsafe link, or occupied migration destination" }
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
