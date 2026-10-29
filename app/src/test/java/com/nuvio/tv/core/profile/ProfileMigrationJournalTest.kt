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
