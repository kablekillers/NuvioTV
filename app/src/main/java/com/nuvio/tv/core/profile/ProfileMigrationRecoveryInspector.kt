package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Read-only inspection of an interrupted profile migration. Never removes
 * partial copies or resumes migration without an explicit recovery decision.
 */
internal object ProfileMigrationRecoveryInspector {
    enum class State { NO_JOURNAL, UNRESOLVED, VERIFIED_NOT_COMPLETE, INVALID_JOURNAL }
    data class Report(val state: State, val accountMatches: Boolean, val reason: String)

    fun inspect(directory: File, verifiedAccountId: String): Report {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return Report(State.INVALID_JOURNAL, false, "Migration directory unavailable")
        }
        if (Files.isSymbolicLink(directory.toPath()) ||
            Files.exists(directory.resolve("profile_migration_journal.tmp").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return Report(State.INVALID_JOURNAL, false, "Unsafe or unfinished journal write")
        }
        val entry = runCatching { ProfileMigrationJournal.read(directory) }
            .getOrElse { return Report(State.INVALID_JOURNAL, false, "Unreadable migration journal") }
            ?: return Report(State.NO_JOURNAL, false, "No migration journal exists")
        val matches = verifiedAccountId.isNotBlank() && entry.accountId == verifiedAccountId
        if (!matches) {
            return Report(State.UNRESOLVED, false, "Journal belongs to a different verified account")
        }
        return when (entry.phase) {
            ProfileMigrationJournal.Phase.PREPARED,
            ProfileMigrationJournal.Phase.COPYING ->
                Report(State.UNRESOLVED, true, "Manual reconciliation required before retry")
            ProfileMigrationJournal.Phase.VERIFIED ->
                Report(State.VERIFIED_NOT_COMPLETE, true, "Copied files do not prove complete migration")
            ProfileMigrationJournal.Phase.COMPLETED ->
                Report(State.UNRESOLVED, true, "Legacy completion marker requires full inventory audit")
        }
    }
}
