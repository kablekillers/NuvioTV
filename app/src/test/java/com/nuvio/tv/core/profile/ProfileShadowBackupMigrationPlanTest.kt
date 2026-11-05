package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileShadowBackupMigrationPlanTest {
    private val primary = mapOf(
        "favorites_p2.preferences_pb" to "favorites_p5.preferences_pb",
        "history_p2.preferences_pb" to "history_p5.preferences_pb"
    )

    @Test fun plansOnlyExistingBackupsWithoutChangingFiles() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            dir.resolve("favorites_p2.preferences_pb.bak").writeText("saved favorites")
            val plan = ProfileShadowBackupMigrationPlan.inspect(dir, 2, 5, primary)
            assertEquals(mapOf(
                "favorites_p2.preferences_pb.bak" to "favorites_p5.preferences_pb.bak"
            ), plan.backupsToCopy)
            assertEquals(listOf("history_p2.preferences_pb"),
                plan.primaryFilesWithoutBackups)
            assertEquals("saved favorites",
                dir.resolve("favorites_p2.preferences_pb.bak").readText())
            assertTrue(!dir.resolve("favorites_p5.preferences_pb.bak").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDestinationBackupsToAvoidOverwrite() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            dir.resolve("history_p5.preferences_pb.bak").writeText("existing data")
            ProfileShadowBackupMigrationPlan.inspect(dir, 2, 5, primary)
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOrphanedBackupThatWouldBeStranded() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            dir.resolve("settings_p2.preferences_pb.bak").writeText("unlisted data")
            ProfileShadowBackupMigrationPlan.inspect(dir, 2, 5, primary)
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedProfileTarget() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            ProfileShadowBackupMigrationPlan.inspect(dir, 2, 7, primary)
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedManifest() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            ProfileShadowBackupMigrationPlan.inspect(
                dir, 2, 5, mapOf("../favorites_p2.preferences_pb" to "favorites_p5.preferences_pb")
            )
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyShadowBackup() {
        val dir = Files.createTempDirectory("profile-backup-plan").toFile()
        try {
            dir.resolve("history_p2.preferences_pb.bak").createNewFile()
            ProfileShadowBackupMigrationPlan.inspect(dir, 2, 5, primary)
        } finally { dir.deleteRecursively() }
    }
}
