package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Files

class ProfileMigrationExclusiveLockTest {
    @Test fun rejectsNestedMigrationAttemptOnSameDirectory() {
        val directory = Files.createTempDirectory("migration-exclusive").toFile()
        try {
            val result = ProfileMigrationExclusiveLock.tryWithLock(directory) {
                val nested = ProfileMigrationExclusiveLock.tryWithLock(directory) { "unsafe" }
                assertNull(nested)
                "first migration holds lock"
            }
            assertEquals("first migration holds lock", result)
            assertEquals("later safe migration",
                ProfileMigrationExclusiveLock.tryWithLock(directory) { "later safe migration" })
            assertTrue(directory.resolve("profile_migration.lock").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun suspendingMigrationRetainsExclusiveLockAcrossSuspension() = runBlocking {
        val directory = Files.createTempDirectory("migration-exclusive").toFile()
        try {
            val result = ProfileMigrationExclusiveLock.tryWithSuspendingLock(directory) {
                yield()
                assertNull(ProfileMigrationExclusiveLock.tryWithLock(directory) { "unsafe" })
                "verified migration owns lock"
            }
            assertEquals("verified migration owns lock", result)
            assertEquals("released", ProfileMigrationExclusiveLock.tryWithLock(directory) { "released" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun releasesLockEvenWhenMigrationThrows() {
        val directory = Files.createTempDirectory("migration-exclusive").toFile()
        try {
            val failed = runCatching {
                ProfileMigrationExclusiveLock.tryWithLock(directory) {
                    error("aborted migration")
                }
            }
            assertTrue(failed.isFailure)
            assertEquals(42, ProfileMigrationExclusiveLock.tryWithLock(directory) { 42 })
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesSymbolicLinkLockFileWithoutChangingTarget() {
        val directory = Files.createTempDirectory("migration-exclusive").toFile()
        val outside = Files.createTempFile("migration-lock-outside", ".txt").toFile()
        try {
            outside.writeText("untouched")
            Files.createSymbolicLink(
                directory.resolve("profile_migration.lock").toPath(),
                outside.toPath()
            )
            assertTrue(runCatching {
                ProfileMigrationExclusiveLock.tryWithLock(directory) { "unsafe" }
            }.isFailure)
            assertEquals("untouched", outside.readText())
        } finally {
            directory.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun refusesNonexistentMigrationDirectory() {
        val directory = Files.createTempDirectory("migration-exclusive").toFile()
        directory.deleteRecursively()
        assertFalse(runCatching {
            ProfileMigrationExclusiveLock.tryWithLock(directory) { "unsafe" }
        }.isSuccess)
    }
}
