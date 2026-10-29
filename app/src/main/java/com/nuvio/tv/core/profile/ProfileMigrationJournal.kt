package com.nuvio.tv.core.profile

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable, fail-closed journal for a future profile migration coordinator.
 * Not wired to account activation or profile mutation.
 *
 * A journal left in PREPARED or COPYING state after a crash must block
 * activation until the caller reconciles it. Never automatically delete
 * source or destination files based on a stale journal.
 */
internal object ProfileMigrationJournal {
    enum class Phase { PREPARED, COPYING, VERIFIED, COMPLETED }
    data class Entry(
        val accountId: String,
        val sourceId: Int,
        val targetId: Int,
        val phase: Phase
    )

    fun write(directory: File, entry: Entry) {
        require(isValidAccountId(entry.accountId))
        require(entry.sourceId > 1 && entry.targetId > 1 && entry.sourceId != entry.targetId)
        require(directory.isDirectory)
        val journal = File(directory, JOURNAL_NAME)
        val previous = read(directory)
        if (previous != null) {
            require(previous.accountId == entry.accountId &&
                previous.sourceId == entry.sourceId && previous.targetId == entry.targetId) {
                "Another profile migration is already journaled"
            }
            require(entry.phase.ordinal >= previous.phase.ordinal) {
                "Profile migration journal cannot move backwards"
            }
        }
        val temp = File(directory, "$JOURNAL_NAME.tmp")
        require(!temp.exists()) { "Unresolved migration journal temporary file" }
        try {
            temp.writeText(
                listOf(VERSION, entry.accountId, entry.sourceId, entry.targetId, entry.phase.name)
                    .joinToString("\n") + "\n"
            )
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            try {
                Files.move(temp.toPath(), journal.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic journal replacement is required", unsupported)
            }
        } finally {
            temp.delete()
        }
    }

    fun read(directory: File): Entry? {
        val journal = File(directory, JOURNAL_NAME)
        if (!journal.exists()) return null
        val lines = journal.readLines()
        require(lines.size == 5 && lines[0] == VERSION) { "Invalid migration journal" }
        val accountId = lines[1]
        val sourceId = lines[2].toInt()
        val targetId = lines[3].toInt()
        val phase = Phase.valueOf(lines[4])
        require(isValidAccountId(accountId) &&
            sourceId > 1 && targetId > 1 && sourceId != targetId) {
            "Invalid migration journal identity"
        }
        return Entry(accountId, sourceId, targetId, phase)
    }

    fun hasUnresolvedMigration(directory: File): Boolean {
        if (File(directory, "$JOURNAL_NAME.tmp").exists()) return true
        val entry = read(directory) ?: return false
        return entry.phase != Phase.COMPLETED
    }

    private fun isValidAccountId(value: String): Boolean =
        value.isNotBlank() && value == value.trim() &&
            value.none { it == '\\n' || it == '\\r' || it.isISOControl() }

    private const val VERSION = "profile-migration-v1"
    private const val JOURNAL_NAME = "profile_migration_journal"
}
