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

    @Test fun guardedPreparationHoldsSourceAndDestinationGateThroughPreview() {
        val filesDir = Files.createTempDirectory("factory-profile-preview").toFile()
        try {
            val dataStore = File(filesDir, "datastore").apply { mkdirs() }
            File(dataStore, "history_p2.preferences_pb").writeText("history")
            File(dataStore, "history_p2.preferences_pb.bak").writeText("history recovery")
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir
            val factory = ProfileDataStoreFactory(context)
            val preparation = factory.prepareProfileRemap(2, 5)
            try {
                assertEquals(mapOf(
                    "history_p2.preferences_pb" to "history_p5.preferences_pb",
                    "history_p2.preferences_pb.bak" to "history_p5.preferences_pb.bak"
                ), preparation.manifest)
                assertTrue(runCatching { factory.get(2, "history") }.isFailure)
                assertTrue(runCatching { factory.get(5, "favorites") }.isFailure)
                assertTrue(runCatching {
                    factory.beginProfileRemapAccessBlock(3, 6)
                }.isFailure)
            } finally {
                preparation.close()
            }
            factory.beginProfileRemapAccessBlock(2, 5).close()
            assertEquals("history recovery",
                File(dataStore, "history_p2.preferences_pb.bak").readText())
            assertTrue(!File(dataStore, "history_p5.preferences_pb").exists())
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun failedGuardedPreparationReleasesBarrierWithoutDeletingData() {
        val filesDir = Files.createTempDirectory("factory-profile-preview").toFile()
        try {
            val dataStore = File(filesDir, "datastore").apply { mkdirs() }
            File(dataStore, "history_p2.preferences_pb").writeText("history")
            File(dataStore, "favorites_p2.preferences_pb.bak").writeText("orphan")
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir
            val factory = ProfileDataStoreFactory(context)
            assertTrue(runCatching { factory.prepareProfileRemap(2, 5) }.isFailure)
            factory.beginProfileRemapAccessBlock(2, 5).close()
            assertEquals("history", File(dataStore, "history_p2.preferences_pb").readText())
            assertEquals("orphan",
                File(dataStore, "favorites_p2.preferences_pb.bak").readText())
        } finally { filesDir.deleteRecursively() }
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
