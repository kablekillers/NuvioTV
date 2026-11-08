package com.nuvio.tv.core.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationCopyVerifierTest {
    @Test fun copiesAndVerifiesWithoutChangingSource() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val source = directory.resolve("source")
            val destination = directory.resolve("destination")
            source.writeText("profile history data")
            assertTrue(ProfileMigrationCopyVerifier.copyAndVerify(source, destination))
            assertEquals(source.readBytes().toList(), destination.readBytes().toList())
            assertEquals("profile history data", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverOverwritesExistingDestination() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val source = directory.resolve("source")
            val destination = directory.resolve("destination")
            source.writeText("source")
            destination.writeText("existing customer data")
            try {
                ProfileMigrationCopyVerifier.copyAndVerify(source, destination)
            } finally {
                assertEquals("existing customer data", destination.readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSameSourceAndDestination() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val source = directory.resolve("source")
            source.writeText("must remain untouched")
            try {
                ProfileMigrationCopyVerifier.copyAndVerify(source, source)
            } finally {
                assertEquals("must remain untouched", source.readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingSource() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            ProfileMigrationCopyVerifier.copyAndVerify(
                directory.resolve("missing"), directory.resolve("destination")
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSourceSymlink() {
        val directory = Files.createTempDirectory("profile-copy").toFile()
        try {
            val original = directory.resolve("original")
            original.writeText("sensitive data")
            val link = directory.resolve("link")
            Files.createSymbolicLink(link.toPath(), original.toPath())
            ProfileMigrationCopyVerifier.copyAndVerify(link, directory.resolve("destination"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
