package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileMigrationCopyCoordinatorTest {
    @Test fun copyPreservesSourceAndLeavesMigrationUnresolved() {
        val dir = Files.createTempDirectory("profile-copy-coordinator").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb").writeText("personal data")
            val result = ProfileMigrationCopyCoordinator.copyWithJournal(
                dir, "verified-account", 2, 7,
                mapOf("settings_p2.preferences_pb" to "settings_p7.preferences_pb")
            )
            assertEquals(listOf("settings_p7.preferences_pb"), result.copiedFileNames)
            assertEquals("personal data", dir.resolve("settings_p2.preferences_pb").readText())
            assertEquals("personal data", dir.resolve("settings_p7.preferences_pb").readText())
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

    @Test fun failedCopyLeavesRecoveryJournalAndPreservesSource() {
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
            assertTrue(ProfileMigrationJournal.hasUnresolvedMigration(dir))
        } finally { dir.deleteRecursively() }
    }
}
