package com.nuvio.tv.core.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileFileCopyTransactionTest {
    @Test fun copiesAndPreservesOriginal() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val source = directory.resolve("history_p2.preferences_pb")
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            source.writeBytes(bytes)
            val result = ProfileFileCopyTransaction.copy(
                directory, mapOf(source.name to "history_p7.preferences_pb")
            )
            assertTrue("history_p7.preferences_pb" in result.copiedFileNames)
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, directory.resolve("history_p7.preferences_pb").readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun occupiedDestinationIsNeverOverwritten() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            directory.resolve("history_p2.preferences_pb").writeText("original")
            val target = directory.resolve("history_p7.preferences_pb")
            target.writeText("existing")
            val failed = runCatching {
                ProfileFileCopyTransaction.copy(
                    directory, mapOf("history_p2.preferences_pb" to target.name)
                )
            }.isFailure
            assertTrue(failed)
            assertTrue(target.readText() == "existing")
        } finally { directory.deleteRecursively() }
    }

    @Test fun failureLeavesOriginalUntouched() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val source = directory.resolve("history_p2.preferences_pb")
            source.writeText("keep me")
            assertTrue(runCatching {
                ProfileFileCopyTransaction.copy(
                    directory, mapOf(source.name to "../outside.preferences_pb")
                )
            }.isFailure)
            assertTrue(source.readText() == "keep me")
            assertFalse(directory.resolve("history_p7.preferences_pb").exists())
        } finally { directory.deleteRecursively() }
    }
}
