package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationMetadataReconciliationTest {
    private fun profile(id: Int, name: String = "Profile $id") =
        UserProfile(id = id, name = name, avatarColorHex = "#123456")

    private val originals = listOf(profile(1), profile(2), profile(4))

    @Test fun acceptsExactMigratedMetadataAndActiveProfile() {
        val expected = DeviceProfileMetadataRemapPlanner.propose(originals, 2, mapOf(2 to 5))
        assertTrue(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5, expected.profiles, expected.activeProfileId
        ))
    }

    @Test fun rejectsStaleActiveSelection() {
        val expected = DeviceProfileMetadataRemapPlanner.propose(originals, 2, mapOf(2 to 5))
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5, expected.profiles, 2
        ))
    }

    @Test fun rejectsOldSourceOrDroppedPersonalProfile() {
        val expected = DeviceProfileMetadataRemapPlanner.propose(originals, 2, mapOf(2 to 5))
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5, originals, expected.activeProfileId
        ))
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5, expected.profiles.dropLast(1), expected.activeProfileId
        ))
    }

    @Test fun rejectsRenamedOrUnsharedPersonalProfile() {
        val expected = DeviceProfileMetadataRemapPlanner.propose(originals, 2, mapOf(2 to 5))
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5,
            expected.profiles.map { if (it.id == 5) it.copy(name = "Wrong person") else it },
            expected.activeProfileId
        ))
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 5,
            expected.profiles.map {
                if (it.id == 5) it.copy(usesPrimaryAddons = false) else it
            }, expected.activeProfileId
        ))
    }

    @Test fun invalidMigrationProposalNeverAuthorizesCompletion() {
        assertFalse(ProfileMigrationMetadataReconciliation.matches(
            originals, 2, 2, 7, originals, 2
        ))
    }
}
