package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationCoordinatorTest {
    @Test fun cleanStorageProducesNonMutatingRemapProposal() {
        val directory = Files.createTempDirectory("profile-preflight").toFile()
        try {
            val result = ProfileMigrationCoordinator.preflight(
                directory, "verified-account", listOf(1, 2, 3), listOf(1, 2, 4)
            )
            assertTrue(result.readyForManualMigration)
            assertEquals(mapOf(2 to 5), result.proposedRemaps)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun invalidIdentityFailsClosed() {
        val directory = Files.createTempDirectory("profile-preflight").toFile()
        try {
            val result = ProfileMigrationCoordinator.preflight(
                directory, "account\nother", listOf(1), listOf(1)
            )
            assertFalse(result.readyForManualMigration)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unresolvedJournalBlocksPreflight() {
        val directory = Files.createTempDirectory("profile-preflight").toFile()
        try {
            ProfileMigrationJournal.write(
                directory, ProfileMigrationJournal.Entry(
                    "verified-account", 2, 3, ProfileMigrationJournal.Phase.PREPARED
                )
            )
            val result = ProfileMigrationCoordinator.preflight(
                directory, "verified-account", listOf(1, 2), listOf(1, 2)
            )
            assertFalse(result.readyForManualMigration)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun impossibleRemapFailsClosed() {
        val directory = Files.createTempDirectory("profile-preflight").toFile()
        try {
            val result = ProfileMigrationCoordinator.preflight(
                directory, "verified-account", listOf(1, 2, 3, 6), listOf(1, 2, 3, 4)
            )
            assertFalse(result.readyForManualMigration)
        } finally {
            directory.deleteRecursively()
        }
    }
}
