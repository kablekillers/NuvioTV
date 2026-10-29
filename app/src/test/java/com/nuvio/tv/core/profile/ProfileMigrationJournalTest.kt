package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileMigrationJournalTest {
    @Test fun incompleteJournalBlocksActivation() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            val prepared = ProfileMigrationJournal.Entry(
                "verified-account", 2, 7, ProfileMigrationJournal.Phase.PREPARED
            )
            ProfileMigrationJournal.write(directory, prepared)
            assertEquals(prepared, ProfileMigrationJournal.read(directory))
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(directory))
            ProfileMigrationJournal.write(
                directory, prepared.copy(phase = ProfileMigrationJournal.Phase.COMPLETED)
            )
            assertFalse(ProfileMigrationJournal.hasUnresolvedMigration(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun differentAccountCannotOverwriteJournal() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            val first = ProfileMigrationJournal.Entry(
                "account-a", 2, 7, ProfileMigrationJournal.Phase.PREPARED
            )
            ProfileMigrationJournal.write(directory, first)
            assertTrue(runCatching {
                ProfileMigrationJournal.write(directory, first.copy(accountId = "account-b"))
            }.isFailure)
            assertEquals(first, ProfileMigrationJournal.read(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun newlineInAccountIdCannotCorruptJournalFormat() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            assertTrue(runCatching {
                ProfileMigrationJournal.write(
                    directory,
                    ProfileMigrationJournal.Entry(
                        "account\\nother", 2, 7, ProfileMigrationJournal.Phase.PREPARED
                    )
                )
            }.isFailure)
            assertFalse(directory.resolve("profile_migration_journal").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unresolvedTemporaryJournalBlocksFurtherWrites() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            directory.resolve("profile_migration_journal.tmp").writeText("interrupted")
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(directory))
            assertTrue(runCatching {
                ProfileMigrationJournal.write(
                    directory,
                    ProfileMigrationJournal.Entry(
                        "account-a", 2, 7, ProfileMigrationJournal.Phase.PREPARED
                    )
                )
            }.isFailure)
            assertEquals("interrupted",
                directory.resolve("profile_migration_journal.tmp").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun journalPhaseCannotMoveBackwards() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            val verified = ProfileMigrationJournal.Entry(
                "account-a", 2, 7, ProfileMigrationJournal.Phase.VERIFIED
            )
            ProfileMigrationJournal.write(directory, verified)
            assertTrue(runCatching {
                ProfileMigrationJournal.write(
                    directory, verified.copy(phase = ProfileMigrationJournal.Phase.COPYING)
                )
            }.isFailure)
            assertEquals(verified, ProfileMigrationJournal.read(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun symbolicLinkCannotRedirectJournalWrite() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        val external = Files.createTempFile("profile-journal-external", ".txt").toFile()
        try {
            external.writeText("preserve")
            Files.createSymbolicLink(
                directory.resolve("profile_migration_journal").toPath(),
                external.toPath()
            )
            assertTrue(runCatching {
                ProfileMigrationJournal.write(
                    directory,
                    ProfileMigrationJournal.Entry(
                        "account-a", 2, 7, ProfileMigrationJournal.Phase.PREPARED
                    )
                )
            }.isFailure)
            assertEquals("preserve", external.readText())
        } finally {
            directory.deleteRecursively()
            external.delete()
        }
    }

    @Test fun missingJournalDirectoryBlocksActivation() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        directory.deleteRecursively()
        assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(directory))
    }

    @Test fun completedJournalDoesNotOverrideInterruptedTemporaryWrite() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            ProfileMigrationJournal.write(
                directory,
                ProfileMigrationJournal.Entry(
                    "account-a", 2, 7, ProfileMigrationJournal.Phase.COMPLETED
                )
            )
            assertFalse(ProfileMigrationJournal.hasUnresolvedMigration(directory))
            directory.resolve("profile_migration_journal.tmp").writeText("interrupted")
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidJournalFailsClosed() {
        val directory = Files.createTempDirectory("profile-journal").toFile()
        try {
            directory.resolve("profile_migration_journal").writeText("broken")
            assertTrue(runCatching {
                ProfileMigrationJournal.hasUnresolvedMigration(directory)
            }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
