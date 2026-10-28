package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileMigrationPlannerTest {
    @Test fun detectsLegacyCloudIdCollisionsWithoutDroppingProfiles() {
        val plan = DeviceProfileMigrationPlanner.inspect(listOf(1, 2, 3), listOf(1, 2, 4))
        assertTrue(plan.requiresLocalIdRemapping)
        assertEquals(listOf(1, 2, 3), plan.preservedCloudIds)
        assertEquals(listOf(1, 2, 4), plan.reservedLocalIds)
    }

    @Test fun mainProfileIdIsIntentionallyShared() {
        val plan = DeviceProfileMigrationPlanner.inspect(listOf(1, 2), listOf(1, 5))
        assertFalse(plan.requiresLocalIdRemapping)
        assertEquals(emptyList<Int>(), plan.collidingSecondaryIds)
    }

    @Test fun deduplicatesIdentifiersWithoutModifyingSource() {
        val plan = DeviceProfileMigrationPlanner.inspect(listOf(3, 1, 3), listOf(1, 4, 4))
        assertEquals(listOf(1, 3), plan.preservedCloudIds)
        assertEquals(listOf(1, 4), plan.reservedLocalIds)
    }

    @Test fun proposesCollisionFreeLocalIdsWithoutChangingCloudIds() {
        val cloud = listOf(1, 2, 3, 6)
        val local = listOf(1, 2, 3, 4)
        val proposal = DeviceProfileMigrationPlanner.proposeLocalRemap(cloud, local)
        assertEquals(mapOf(2 to 5, 3 to 7), proposal)
        assertEquals(listOf(1, 2, 3, 6), cloud)
        assertEquals(listOf(1, 2, 3, 4), local)
        assertFalse(proposal.values.any { it in cloud || it in local })
    }

    @Test fun noCollisionsProduceNoRemapping() {
        assertTrue(DeviceProfileMigrationPlanner.proposeLocalRemap(listOf(1, 2), listOf(1, 5)).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun remapRejectsMoreThanFivePersonalProfiles() {
        DeviceProfileMigrationPlanner.proposeLocalRemap(
            listOf(1, 2),
            listOf(1, 2, 3, 4, 5, 6, 7)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun remapRejectsDuplicateDeviceIds() {
        DeviceProfileMigrationPlanner.proposeLocalRemap(listOf(1, 2), listOf(1, 2, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun remapRejectsMissingCloudMain() {
        DeviceProfileMigrationPlanner.proposeLocalRemap(listOf(2, 3), listOf(1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidProfileIds() {
        DeviceProfileMigrationPlanner.inspect(listOf(1, 0), listOf(1, 2))
    }
}
