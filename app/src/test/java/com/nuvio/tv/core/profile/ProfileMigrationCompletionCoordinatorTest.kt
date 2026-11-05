package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileMigrationCompletionCoordinatorTest {
    private val originals = listOf(
        UserProfile(id = 1, name = "Main", avatarColorHex = "#123456"),
        UserProfile(id = 2, name = "Personal", avatarColorHex = "#234567")
    )
    private val manifest = mapOf(
        "history_p2.preferences_pb" to "history_p5.preferences_pb"
    )

    private fun preparedDirectory(): File {
        val dir = Files.createTempDirectory("profile-completion").toFile()
        dir.resolve("history_p2.preferences_pb").writeText("preserve personal history")
        dir.resolve("history_p5.preferences_pb").writeText("preserve personal history")
        ProfileMigrationJournal.write(
            dir, ProfileMigrationJournal.Entry(
                "customer-a", 2, 5, ProfileMigrationJournal.Phase.VERIFIED
            )
        )
        return dir
    }

    private suspend fun attempt(dir: File, store: ProfileDataStore, account: String = "customer-a") =
        ProfileMigrationCompletionCoordinator.completeVerifiedRemap(
            dir, account, 2, 5, originals, 2, manifest, store
        )

    @Test fun completesOnlyAfterReadbackMetadataMatches() = runBlocking {
        val dir = preparedDirectory()
        try {
            val store = mockk<ProfileDataStore>()
            coEvery {
                store.verifyPersistedProfileMetadataRemap(
                    "customer-a", 2, 5, originals, 2, manifest
                )
            } returns true

            assertTrue(attempt(dir, store))
            assertEquals(ProfileMigrationJournal.Phase.COMPLETED,
                ProfileMigrationJournal.read(dir)?.phase)
            assertEquals("preserve personal history",
                dir.resolve("history_p2.preferences_pb").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun mismatchDoesNotMarkMigrationCompleted() = runBlocking {
        val dir = preparedDirectory()
        try {
            val store = mockk<ProfileDataStore>()
            coEvery {
                store.verifyPersistedProfileMetadataRemap(
                    "customer-a", 2, 5, originals, 2, manifest
                )
            } returns false

            assertFalse(attempt(dir, store))
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(dir)?.phase)
        } finally { dir.deleteRecursively() }
    }

    @Test fun wrongAccountAndCorruptedCopyLeaveJournalUnresolved() = runBlocking {
        val dir = preparedDirectory()
        try {
            val store = mockk<ProfileDataStore>()
            assertFalse(attempt(dir, store, "customer-b"))
            dir.resolve("history_p5.preferences_pb").writeText("changed")
            assertFalse(attempt(dir, store))
            assertEquals(ProfileMigrationJournal.Phase.VERIFIED,
                ProfileMigrationJournal.read(dir)?.phase)
        } finally { dir.deleteRecursively() }
    }
}
