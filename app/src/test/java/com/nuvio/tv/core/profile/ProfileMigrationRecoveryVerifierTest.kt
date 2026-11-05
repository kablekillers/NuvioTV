package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileMigrationRecoveryVerifierTest {
    private val manifest = mapOf(
        "watch_progress_p2.preferences_pb" to "watch_progress_p5.preferences_pb",
        "favorites_p2.preferences_pb" to "favorites_p5.preferences_pb"
    )

    private fun verifiedDirectory(): java.io.File {
        val dir = Files.createTempDirectory("profile-recovery").toFile()
        dir.resolve("watch_progress_p2.preferences_pb").writeText("private watch progress")
        dir.resolve("watch_progress_p5.preferences_pb").writeText("private watch progress")
        dir.resolve("favorites_p2.preferences_pb").writeText("private favorites")
        dir.resolve("favorites_p5.preferences_pb").writeText("private favorites")
        ProfileMigrationJournal.write(
            dir,
            ProfileMigrationJournal.Entry(
                "customer-a", 2, 5, ProfileMigrationJournal.Phase.VERIFIED
            )
        )
        return dir
    }

    @Test fun acceptsMatchingCopyOnlyAfterVerifiedJournal() {
        val dir = verifiedDirectory()
        try {
            assertTrue(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            assertTrue(dir.resolve("watch_progress_p2.preferences_pb").exists())
            assertTrue(dir.resolve("favorites_p2.preferences_pb").exists())
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsChangedDestinationWithoutDeletingIt() {
        val dir = verifiedDirectory()
        try {
            dir.resolve("favorites_p5.preferences_pb").writeText("corrupted")
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            assertTrue(dir.resolve("favorites_p5.preferences_pb").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsOtherAccountsAndWrongIds() {
        val dir = verifiedDirectory()
        try {
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-b", 2, 5, manifest
            ))
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 6, manifest
            ))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsIncompleteJournalAndTemporaryWrite() {
        val dir = verifiedDirectory()
        try {
            ProfileMigrationJournal.write(
                dir, ProfileMigrationJournal.Entry(
                    "customer-a", 2, 5, ProfileMigrationJournal.Phase.COMPLETED
                )
            )
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            dir.resolve("profile_migration_journal.tmp").writeText("interrupted")
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsManifestThatOmitsExistingPersonalDataStore() {
        val dir = verifiedDirectory()
        try {
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5,
                mapOf("favorites_p2.preferences_pb" to "favorites_p5.preferences_pb")
            ))
            assertTrue(dir.resolve("watch_progress_p2.preferences_pb").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsUnexpectedDestinationStoreNotRepresentedInManifest() {
        val dir = verifiedDirectory()
        try {
            dir.resolve("history_p5.preferences_pb").writeText("unexpected previous profile")
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            assertTrue(dir.resolve("history_p5.preferences_pb").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsUnmigratedSourceShadowBackup() {
        val dir = verifiedDirectory()
        try {
            dir.resolve("favorites_p2.preferences_pb.bak").writeText("backup private favorites")
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            assertTrue(dir.resolve("favorites_p2.preferences_pb.bak").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsUnmigratedDestinationShadowBackup() {
        val dir = verifiedDirectory()
        try {
            dir.resolve("favorites_p5.preferences_pb.bak").writeText("other profile backup")
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
            assertTrue(dir.resolve("favorites_p5.preferences_pb.bak").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsMissingOrUnsafeManifestAndMissingFiles() {
        val dir = verifiedDirectory()
        try {
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, emptyMap()
            ))
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5,
                mapOf("../favorites_p2.preferences_pb" to "favorites_p5.preferences_pb")
            ))
            dir.resolve("watch_progress_p5.preferences_pb").delete()
            assertFalse(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                dir, "customer-a", 2, 5, manifest
            ))
        } finally { dir.deleteRecursively() }
    }
}
