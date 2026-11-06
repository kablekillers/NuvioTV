package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileDataStoreFactoryShadowCopyGateTest {
    @Test fun blockedShadowCopyIsNotReportedAsWritten() {
        val filesDir = Files.createTempDirectory("profile-shadow-gate").toFile()
        try {
            val dataStoreDir = File(filesDir, "datastore").apply { mkdirs() }
            File(dataStoreDir, "history_p2.preferences_pb").writeText("personal progress")
            val backup = File(dataStoreDir, "history_p2.preferences_pb.bak")
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir
            val factory = ProfileDataStoreFactory(context)

            val barrier = factory.beginProfileRemapAccessBlock(2, 5)
            try {
                assertFalse(factory.writeShadowCopy("history_p2", emptyPreferences()))
                assertFalse("Blocked backup write must not create a file", backup.exists())
            } finally { barrier.close() }

            assertTrue(factory.writeShadowCopy("history_p2", emptyPreferences()))
            assertEquals("personal progress", backup.readText())
        } finally { filesDir.deleteRecursively() }
    }
}
