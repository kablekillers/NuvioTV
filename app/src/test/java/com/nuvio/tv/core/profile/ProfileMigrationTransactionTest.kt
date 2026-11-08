package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationTransactionTest {
    @Test fun journaledBatchVerifiesFilesButDoesNotCompleteMigration() {
        val directory = Files.createTempDirectory("profile-transaction").toFile()
        try {
            val history = directory.resolve("history")
            val watchlist = directory.resolve("watchlist")
            history.writeText("progress")
            watchlist.writeText("favorites")
            val result = ProfileMigrationTransaction.copyBatchWithJournal(
                directory, "verified-account", 2, 3, listOf(
                    ProfileMigrationBatchCopy.Item(history, directory.resolve("history-copy")),
                    ProfileMigrationBatchCopy.Item(watchlist, directory.resolve("watchlist-copy"))
                )
            )
            assertTrue(result.allVerified)
            assertEquals(2, result.verifiedFiles)
            assertEquals("progress", history.readText())
            assertEquals("favorites", watchlist.readText())
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(directory)?.phase)
            assertFalse(ProfileMigrationJournal.hasCompletedMigrationForAccount(
                directory, "verified-account"
            ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun verifiedCopyDoesNotMarkEntireMigrationComplete() {
        val directory = Files.createTempDirectory("profile-transaction").toFile()
        try {
            val source = directory.resolve("source")
            val destination = directory.resolve("destination")
            source.writeText("watch history")
            assertTrue(ProfileMigrationTransaction.copyWithJournal(
                directory, "verified-account", 2, 3, source, destination
            ))
            assertEquals("watch history", source.readText())
            assertEquals("watch history", destination.readText())
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(directory)?.phase)
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(directory, "verified-account"))
            assertFalse(ProfileMigrationJournal.hasCompletedMigrationForAccount(
                directory, "verified-account"
            ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun existingDestinationIsRejectedBeforeJournalCreation() {
        val directory = Files.createTempDirectory("profile-transaction").toFile()
        try {
            val source = directory.resolve("source")
            val destination = directory.resolve("destination")
            source.writeText("original")
            destination.writeText("other profile")
            try {
                ProfileMigrationTransaction.copyWithJournal(
                    directory, "verified-account", 2, 3, source, destination
                )
            } finally {
                assertEquals("other profile", destination.readText())
                assertEquals("original", source.readText())
                assertEquals(null, ProfileMigrationJournal.read(directory))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptJournalBlocksNewMigrationWithoutTouchingData() {
        val directory = Files.createTempDirectory("profile-transaction").toFile()
        try {
            directory.resolve("profile_migration_journal").writeText("corrupted")
            val source = directory.resolve("source")
            val destination = directory.resolve("destination")
            source.writeText("original")
            try {
                ProfileMigrationTransaction.copyWithJournal(
                    directory, "verified-account", 2, 3, source, destination
                )
            } finally {
                assertEquals("original", source.readText())
                assertFalse(destination.exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun secondMigrationCannotReuseUnresolvedJournal() {
        val directory = Files.createTempDirectory("profile-transaction").toFile()
        try {
            val source = directory.resolve("source")
            source.writeText("data")
            ProfileMigrationTransaction.copyWithJournal(
                directory, "verified-account", 2, 3, source, directory.resolve("destination")
            )
            ProfileMigrationTransaction.copyWithJournal(
                directory, "verified-account", 4, 5, source, directory.resolve("another")
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
