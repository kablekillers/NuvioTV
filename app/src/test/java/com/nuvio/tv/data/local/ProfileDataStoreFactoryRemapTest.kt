package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileDataStoreFactoryRemapTest {
    @Test fun previewIncludesShadowBackupToKeepRecoveryDataWithProfile() {
        val filesDir = Files.createTempDirectory("factory-profile-preview").toFile()
        try {
            val dataStore = File(filesDir, "datastore").apply { mkdirs() }
            File(dataStore, "history_p2.preferences_pb").writeText("private history")
            File(dataStore, "history_p2.preferences_pb.bak").writeText("recovery history")
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir
            val factory = ProfileDataStoreFactory(context)

            assertEquals(mapOf(
                "history_p2.preferences_pb" to "history_p5.preferences_pb",
                "history_p2.preferences_pb.bak" to "history_p5.preferences_pb.bak"
            ), factory.previewProfileFileMoves(2, 5))
            assertEquals("recovery history",
                File(dataStore, "history_p2.preferences_pb.bak").readText())
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun doubleClosingOldTokenCannotReleaseNewAccessBarrier() {
        val factory = ProfileDataStoreFactory(mockk())
        val first = factory.beginProfileRemapAccessBlock(2, 5)
        assertTrue(runCatching { factory.get(2, "history") }.isFailure)
        first.close()

        val second = factory.beginProfileRemapAccessBlock(2, 5)
        try {
            first.close()
            assertTrue(runCatching { factory.get(2, "history") }.isFailure)
            assertTrue(runCatching { factory.get(5, "favorites") }.isFailure)
        } finally {
            second.close()
        }
    }

    @Test fun previewRejectsOrphanedShadowBackup() {
        val filesDir = Files.createTempDirectory("factory-profile-preview").toFile()
        try {
            val dataStore = File(filesDir, "datastore").apply { mkdirs() }
            File(dataStore, "history_p2.preferences_pb").writeText("private history")
            File(dataStore, "favorites_p2.preferences_pb.bak").writeText("unlisted backup")
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir
            val factory = ProfileDataStoreFactory(context)
            assertTrue(runCatching { factory.previewProfileFileMoves(2, 5) }.isFailure)
            assertEquals("unlisted backup",
                File(dataStore, "favorites_p2.preferences_pb.bak").readText())
        } finally { filesDir.deleteRecursively() }
    }
}
