package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            ))
            assertEquals(2, items.size)
            assertTrue(items.any { it.destination.name.endsWith(".bak") })
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        val dir = Files.createTempDirectory("profile-stores").toFile()
        try {
            ProfileMigrationStoreManifest.fromFactoryPreview(
                dir, mapOf("../other.preferences_pb" to "history_p3.preferences_pb")
            )
        } finally { dir.deleteRecursively() }
    }
}
