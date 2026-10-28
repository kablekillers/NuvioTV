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
    }

    @Test fun deduplicatesIdentifiersWithoutModifyingSource() {
        val plan = DeviceProfileMigrationPlanner.inspect(listOf(3, 1, 3), listOf(1, 4, 4))
        assertEquals(listOf(1, 3), plan.preservedCloudIds)
        assertEquals(listOf(1, 4), plan.reservedLocalIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidProfileIds() {
        DeviceProfileMigrationPlanner.inspect(listOf(1, 0), listOf(1, 2))
    }
}
