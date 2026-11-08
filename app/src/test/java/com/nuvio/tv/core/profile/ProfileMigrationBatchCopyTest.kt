package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationBatchCopyTest {
    @Test fun copiesEveryEnumeratedStoreWithoutDeletingSources() {
        val dir = Files.createTempDirectory("profile-batch").toFile()
        try {
            val first = dir.resolve("history")
            val second = dir.resolve("watchlist")
            first.writeText("progress")
            second.writeText("saved titles")
            val result = ProfileMigrationBatchCopy.copyAll(listOf(
                ProfileMigrationBatchCopy.Item(first, dir.resolve("history-copy")),
                ProfileMigrationBatchCopy.Item(second, dir.resolve("watchlist-copy"))
            ))
            assertTrue(result.allVerified)
            assertEquals(2, result.verifiedFiles)
            assertEquals("progress", first.readText())
            assertEquals("saved titles", second.readText())
            assertEquals("progress", dir.resolve("history-copy").readText())
            assertEquals("saved titles", dir.resolve("watchlist-copy").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun occupiedLaterDestinationPreventsEarlierCopy() {
        val dir = Files.createTempDirectory("profile-batch").toFile()
        try {
            val first = dir.resolve("first")
            val second = dir.resolve("second")
            first.writeText("one")
            second.writeText("two")
            val occupied = dir.resolve("occupied")
            occupied.writeText("other customer")
            val failed = runCatching {
                ProfileMigrationBatchCopy.copyAll(listOf(
                    ProfileMigrationBatchCopy.Item(first, dir.resolve("first-copy")),
                    ProfileMigrationBatchCopy.Item(second, occupied)
                ))
            }.isFailure
            assertTrue(failed)
            assertFalse(dir.resolve("first-copy").exists())
            assertEquals("other customer", occupied.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateDestinationsAreRejected() {
        val dir = Files.createTempDirectory("profile-batch").toFile()
        try {
            val source = dir.resolve("source")
            source.writeText("data")
            ProfileMigrationBatchCopy.copyAll(listOf(
                ProfileMigrationBatchCopy.Item(source, dir.resolve("copy")),
                ProfileMigrationBatchCopy.Item(source, dir.resolve("copy"))
            ))
        } finally {
            dir.deleteRecursively()
        }
    }
}
