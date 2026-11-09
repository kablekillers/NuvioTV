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

    @Test fun symlinkedSourceIsRejectedWithoutCreatingDestination() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val realSource = directory.resolve("real_p2.preferences_pb")
            realSource.writeText("private profile data")
            val link = directory.resolve("history_p2.preferences_pb")
            Files.createSymbolicLink(link.toPath(), realSource.toPath())
            val destination = directory.resolve("history_p7.preferences_pb")
            assertTrue(runCatching {
                ProfileFileCopyTransaction.copy(
                    directory, mapOf(link.name to destination.name)
                )
            }.isFailure)
            assertFalse(destination.exists())
            assertTrue(realSource.readText() == "private profile data")
        } finally { directory.deleteRecursively() }
    }

    @Test fun copiesPrimaryAndShadowBackupWithoutDeletingSources() {
        val directory = Files.createTempDirectory("profile-copy-backup").toFile()
        try {
            val main = directory.resolve("history_p2.preferences_pb")
            val backup = directory.resolve("history_p2.preferences_pb.bak")
            main.writeText("current viewing progress")
            backup.writeText("recoverable viewing progress")
            val result = ProfileFileCopyTransaction.copy(directory, mapOf(
                main.name to "history_p5.preferences_pb",
                backup.name to "history_p5.preferences_pb.bak"
            ))
            assertTrue(result.copiedFileNames.contains("history_p5.preferences_pb"))
            assertTrue(result.copiedFileNames.contains("history_p5.preferences_pb.bak"))
            assertArrayEquals(main.readBytes(),
                directory.resolve("history_p5.preferences_pb").readBytes())
            assertArrayEquals(backup.readBytes(),
                directory.resolve("history_p5.preferences_pb.bak").readBytes())
            assertTrue(main.exists())
            assertTrue(backup.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun existingBackupDestinationCannotBeOverwritten() {
        val directory = Files.createTempDirectory("profile-copy-backup").toFile()
        try {
            directory.resolve("history_p2.preferences_pb.bak").writeText("source")
            val target = directory.resolve("history_p5.preferences_pb.bak")
            target.writeText("private target backup")
            assertTrue(runCatching {
                ProfileFileCopyTransaction.copy(directory,
                    mapOf("history_p2.preferences_pb.bak" to target.name))
            }.isFailure)
            assertTrue(target.readText() == "private target backup")
        } finally { directory.deleteRecursively() }
    }

    @Test fun cannotRenameShadowBackupAsPrimaryDataStore() {
        val directory = Files.createTempDirectory("profile-copy-backup").toFile()
        try {
            directory.resolve("history_p2.preferences_pb.bak").writeText("old state")
            assertTrue(runCatching {
                ProfileFileCopyTransaction.copy(directory,
                    mapOf("history_p2.preferences_pb.bak" to "history_p5.preferences_pb"))
            }.isFailure)
            assertFalse(directory.resolve("history_p5.preferences_pb").exists())
        } finally { directory.deleteRecursively() }
    }


}
