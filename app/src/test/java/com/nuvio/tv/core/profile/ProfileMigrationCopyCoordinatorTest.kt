package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileMigrationCopyCoordinatorTest {
    private fun writeJournalThrough(directory: java.io.File, entry: ProfileMigrationJournal.Entry) {
        for (phase in ProfileMigrationJournal.Phase.values()) {
            ProfileMigrationJournal.write(directory, entry.copy(phase = phase))
            if (phase == entry.phase) break
        }
    }


    @Test fun copyPreservesSourceAndLeavesMigrationUnresolved() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("personal data")
            val result = ProfileMigrationCopyCoordinator.copyWithJournal(
                dir, "verified-account", 2, 5,
                mapOf("settings_p2.preferences_pb" to "settings_p5.preferences_pb")
            )
            assertEquals(listOf("settings_p5.preferences_pb"), result.copiedFileNames)
            assertEquals("personal data", dir.resolve("settings_p2.preferences_pb").readText())
            assertEquals("personal data", dir.resolve("settings_p5.preferences_pb").readText())
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(dir)?.phase)
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(dir))
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 8,
                    mapOf("settings_p2.preferences_pb" to "settings_p8.preferences_pb")
                )
            }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentCopyCannotWriteJournalOrDestination() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("personal data")
            ProfileMigrationExclusiveLock.tryWithLock(dir) {
                assertTrue(runCatching {
                    ProfileMigrationCopyCoordinator.copyWithJournal(
                        dir, "verified-account", 2, 5,
                        mapOf("settings_p2.preferences_pb" to "settings_p5.preferences_pb")
                    )
                }.isFailure)
                assertTrue(ProfileMigrationJournal.read(dir) == null)
                assertTrue(!dir.resolve("settings_p5.preferences_pb").exists())
            }
            assertEquals("personal data", dir.resolve("settings_p2.preferences_pb").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun copiesCompletePrimaryAndShadowBackupManifest() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("private settings")
            dir.resolve("settings_p2.preferences_pb.bak").writeText("recoverable settings")
            val manifest = mapOf(
                "settings_p2.preferences_pb" to "settings_p5.preferences_pb",
                "settings_p2.preferences_pb.bak" to "settings_p5.preferences_pb.bak"
            )
            val result = ProfileMigrationCopyCoordinator.copyWithJournal(
                dir, "verified-account", 2, 5, manifest
            )
            assertEquals(manifest.values.toSet(), result.copiedFileNames.toSet())
            assertTrue(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "verified-account", 2, 5, manifest
            ))
            assertEquals("recoverable settings",
                dir.resolve("settings_p2.preferences_pb.bak").readText())
            assertEquals("recoverable settings",
                dir.resolve("settings_p5.preferences_pb.bak").readText())
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(dir)?.phase)
        } finally { dir.deleteRecursively() }
    }

    @Test fun shadowBackupBlocksCopyBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("private settings")
            dir.resolve("settings_p2.preferences_pb.bak").writeText("recovery settings")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 5,
                    mapOf("settings_p2.preferences_pb" to "settings_p5.preferences_pb")
                )
            }.isFailure)
            assertTrue(ProfileMigrationJournal.read(dir) == null)
            assertTrue(!dir.resolve("settings_p5.preferences_pb").exists())
            assertEquals("recovery settings",
                dir.resolve("settings_p2.preferences_pb.bak").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteManifestFailsBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("settings")
            dir.resolve("history_p2.preferences_pb").writeText("private history")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 5,
                    mapOf("settings_p2.preferences_pb" to "settings_p5.preferences_pb")
                )
            }.isFailure)
            assertTrue(ProfileMigrationJournal.read(dir) == null)
            assertTrue(!dir.resolve("settings_p5.preferences_pb").exists())
            assertEquals("private history", dir.resolve("history_p2.preferences_pb").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unsupportedTargetFailsBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("original")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 7,
                    mapOf("settings_p2.preferences_pb" to "settings_p7.preferences_pb")
                )
            }.isFailure)
            assertTrue(ProfileMigrationJournal.read(dir) == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun wrongProfileManifestIsRejectedBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("original")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 7,
                    mapOf("settings_p2.preferences_pb" to "settings_p8.preferences_pb")
                )
            }.isFailure)
            assertEquals("original", dir.resolve("settings_p2.preferences_pb").readText())
            assertTrue(ProfileMigrationJournal.read(dir) == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unrelatedFilesCannotBeIncludedInProfileMigration() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("original")
            dir.resolve("settings.preferences_pb").writeText("shared main data")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 7,
                    mapOf(
                        "settings_p2.preferences_pb" to "settings_p7.preferences_pb",
                        "settings.preferences_pb" to "settings_p9.preferences_pb"
                    )
                )
            }.isFailure)
            assertEquals("shared main data",
                dir.resolve("settings.preferences_pb").readText())
            assertTrue(!dir.resolve("settings_p7.preferences_pb").exists())
            assertTrue(ProfileMigrationJournal.read(dir) == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unsafePathIsRejectedBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 7,
                    mapOf("../settings_p2.preferences_pb" to "settings_p7.preferences_pb")
                )
            }.isFailure)
            assertTrue(ProfileMigrationJournal.read(dir) == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun recoveryAssessmentNeverClearsJournalForDifferentAccount() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            writeJournalThrough(
                dir,
                ProfileMigrationJournal.Entry(
                    "account-a", 2, 7, ProfileMigrationJournal.Phase.COPYING
                )
            )
            val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-b")
            assertTrue(assessment.requiresManualReconciliation)
            assertTrue(!assessment.accountMatches)
            assertEquals(ProfileMigrationJournal.Phase.COPYING, assessment.phase)
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedJournalStillRequiresExplicitReconciliation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            writeJournalThrough(
                dir,
                ProfileMigrationJournal.Entry(
                    "account-a", 2, 7, ProfileMigrationJournal.Phase.COMPLETED
                )
            )
            val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-a")
            assertTrue(assessment.requiresManualReconciliation)
            assertTrue(assessment.accountMatches)
            assertEquals(ProfileMigrationJournal.Phase.COMPLETED, assessment.phase)
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedTemporaryJournalCannotBeSilentlyIgnored() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("profile_migration_journal.tmp").writeText("partial")
            val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-a")
            assertTrue(assessment.requiresManualReconciliation)
            assertTrue(!assessment.accountMatches)
            assertTrue(assessment.phase == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedJournalIsBlockedWithoutMutation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            val journal = dir.resolve("profile_migration_journal")
            journal.writeText("invalid journal contents")
            val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-a")
            assertTrue(assessment.requiresManualReconciliation)
            assertTrue(!assessment.accountMatches)
            assertTrue(assessment.phase == null)
            assertEquals("invalid journal contents", journal.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedJournalWithTemporaryFileRemainsBlocked() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("profile_migration_journal").writeText("corrupt")
            dir.resolve("profile_migration_journal.tmp").writeText("interrupted")
            val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-a")
            assertTrue(assessment.requiresManualReconciliation)
            assertTrue(!assessment.accountMatches)
            assertTrue(assessment.phase == null)
            assertEquals("corrupt", dir.resolve("profile_migration_journal").readText())
            assertEquals("interrupted", dir.resolve("profile_migration_journal.tmp").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingRecoveryDirectoryFailsClosed() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        dir.deleteRecursively()
        val assessment = ProfileMigrationCopyCoordinator.assessRecovery(dir, "account-a")
        assertTrue(assessment.requiresManualReconciliation)
        assertTrue(!assessment.accountMatches)
        assertTrue(assessment.phase == null)
    }

    @Test fun occupiedDestinationIsRejectedBeforeJournalCreation() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("original")
            dir.resolve("settings_p7.preferences_pb").writeText("existing")
            assertTrue(runCatching {
                ProfileMigrationCopyCoordinator.copyWithJournal(
                    dir, "verified-account", 2, 7,
                    mapOf("settings_p2.preferences_pb" to "settings_p7.preferences_pb")
                )
            }.isFailure)
            assertEquals("original", dir.resolve("settings_p2.preferences_pb").readText())
            assertEquals("existing", dir.resolve("settings_p7.preferences_pb").readText())
            assertTrue(ProfileMigrationJournal.read(dir) == null)
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidAccountIdsNeverCreateMigrationFiles() {
        val invalidIds = listOf("", " ", " account-a", "account-a ", "account\nother", "account\rother")
        for (accountId in invalidIds) {
            val dir = Files.createTempDirectory("profile-copy-invalid-account").toFile()
            try {
                val source = dir.resolve("settings_p2.preferences_pb")
                source.writeText("preserve viewing data")
                assertTrue("Must reject account ID: " + accountId, runCatching {
                    ProfileMigrationCopyCoordinator.copyWithJournal(
                        dir, accountId, 2, 5,
                        mapOf("settings_p2.preferences_pb" to "settings_p5.preferences_pb")
                    )
                }.isFailure)
                assertEquals("preserve viewing data", source.readText())
                assertTrue(!dir.resolve("settings_p5.preferences_pb").exists())
                assertTrue(!dir.resolve("profile_migration_journal").exists())
                assertTrue(!dir.resolve("profile_migration_journal.tmp").exists())
            } finally { dir.deleteRecursively() }
        }
    }

}
