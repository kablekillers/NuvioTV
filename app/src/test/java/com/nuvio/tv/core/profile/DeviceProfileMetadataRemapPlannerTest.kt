package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileMetadataRemapPlannerTest {
    private fun profile(id: Int) = UserProfile(
        id = id, name = "Person $id", avatarColorHex = "#123456",
        usesPrimaryAddons = false, usesPrimaryPlugins = false
    )

    @Test fun remapsMetadataAndActiveSelectionWithoutMutatingOriginalProfiles() {
        val original = listOf(profile(1), profile(2), profile(4))
        val proposed = DeviceProfileMetadataRemapPlanner.propose(original, 2, mapOf(2 to 5))

        assertEquals(listOf(1, 5, 4), proposed.profiles.map { it.id })
        assertEquals(listOf("Person 1", "Person 2", "Person 4"),
            proposed.profiles.map { it.name })
        assertEquals(5, proposed.activeProfileId)
        assertTrue(proposed.profiles.drop(1).all {
            it.usesPrimaryAddons && it.usesPrimaryPlugins
        })
        assertEquals(listOf(1, 2, 4), original.map { it.id })
        assertFalse(original[1].usesPrimaryAddons)
    }

    @Test fun unremappedSecondaryAndMainKeepSelection() {
        val proposed = DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2), profile(4)), 4, mapOf(2 to 5)
        )
        assertEquals(4, proposed.activeProfileId)
        assertEquals(1, proposed.profiles.first().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToOverwriteExistingProfile() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2), profile(4)), 2, mapOf(2 to 4)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesOutOfRangeDestination() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2)), 2, mapOf(2 to 7)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesDuplicateDestination() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2), profile(3)), 2,
            mapOf(2 to 5, 3 to 5)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMissingActiveProfile() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2)), 3, mapOf(2 to 5)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMissingMainProfile() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(2)), 2, emptyMap()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownSourceProfile() {
        DeviceProfileMetadataRemapPlanner.propose(
            listOf(profile(1), profile(2)), 2, mapOf(3 to 5)
        )
    }
}
