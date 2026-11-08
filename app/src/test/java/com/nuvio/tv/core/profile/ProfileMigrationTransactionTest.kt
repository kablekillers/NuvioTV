package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationTransactionTest {
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
    fun existingDestinationLeavesRecoveryJournalUnresolved() {
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
                assertEquals(ProfileMigrationJournal.Phase.COPYING,
                    ProfileMigrationJournal.read(directory)?.phase)
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
