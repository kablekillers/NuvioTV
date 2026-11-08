package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileMigrationStoreManifestTest {
    @Test fun includesPrimaryAndShadowBackupFromFactoryPreview() {
        val dir = Files.createTempDirectory("profile-stores").toFile()
        try {
            dir.resolve("history_p2.preferences_pb").writeText("history")
            dir.resolve("history_p2.preferences_pb.bak").writeText("backup")
            val items = ProfileMigrationStoreManifest.fromFactoryPreview(dir, mapOf(
                "history_p2.preferences_pb" to "history_p3.preferences_pb",
                "history_p2.preferences_pb.bak" to "history_p3.preferences_pb.bak"
            ), 2, 3)
            assertEquals(2, items.size)
            assertTrue(items.any { it.destination.name.endsWith(".bak") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun copiesRealStoreManifestWithJournalWithoutActivatingAccount() {
        val dir = Files.createTempDirectory("profile-migration-integration").toFile()
        try {
            dir.resolve("history_p2.preferences_pb").writeText("private history")
            dir.resolve("history_p2.preferences_pb.bak").writeText("shadow backup")
            val items = ProfileMigrationStoreManifest.fromFactoryPreview(
                dir, mapOf(
                    "history_p2.preferences_pb" to "history_p3.preferences_pb",
                    "history_p2.preferences_pb.bak" to "history_p3.preferences_pb.bak"
                ), 2, 3
            )
            val result = ProfileMigrationTransaction.copyBatchWithJournal(
                dir, "verified-account-123", 2, 3, items
            )
            assertTrue(result.allVerified)
            assertEquals(2, result.verifiedFiles)
            assertEquals("private history", dir.resolve("history_p3.preferences_pb").readText())
            assertEquals("shadow backup", dir.resolve("history_p3.preferences_pb.bak").readText())
            assertEquals("private history", dir.resolve("history_p2.preferences_pb").readText())
            assertEquals(
                ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(dir)?.phase
            )
            assertFalse(ProfileMigrationJournal.hasCompletedMigrationForAccount(
                dir, "verified-account-123"
            ))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidManifestDoesNotCreateJournalOrDestination() {
        val dir = Files.createTempDirectory("profile-migration-invalid").toFile()
        try {
            dir.resolve("history_p2.preferences_pb").writeText("private history")
            val failed = runCatching {
                val items = ProfileMigrationStoreManifest.fromFactoryPreview(
                    dir, mapOf("history_p2.preferences_pb" to "history_p4.preferences_pb"),
                    2, 3
                )
                ProfileMigrationTransaction.copyBatchWithJournal(
                    dir, "verified-account-123", 2, 3, items
                )
            }
            assertTrue(failed.isFailure)
            assertFalse(dir.resolve("profile_migration_journal").exists())
            assertFalse(dir.resolve("history_p4.preferences_pb").exists())
            assertEquals("private history", dir.resolve("history_p2.preferences_pb").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongTargetProfile() {
        val dir = Files.createTempDirectory("profile-stores").toFile()
        try {
            dir.resolve("history_p2.preferences_pb").writeText("history")
            ProfileMigrationStoreManifest.fromFactoryPreview(
                dir, mapOf("history_p2.preferences_pb" to "history_p4.preferences_pb"), 2, 3
            )
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        val dir = Files.createTempDirectory("profile-stores").toFile()
        try {
            ProfileMigrationStoreManifest.fromFactoryPreview(
                dir, mapOf("../other.preferences_pb" to "history_p3.preferences_pb"), 2, 3
            )
        } finally { dir.deleteRecursively() }
    }
}
