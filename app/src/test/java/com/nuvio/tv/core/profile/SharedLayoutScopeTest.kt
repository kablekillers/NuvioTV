package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLayoutScopeTest {
    @Test fun legacyProfilesKeepOwnLayoutUntilMigration() {
        assertEquals(3, SharedLayoutScope.configurationProfileId(3, false))
        assertTrue(SharedLayoutScope.canEditConfiguration(3, false))
    }

    @Test fun deviceLocalProfilesUseMainLayout() {
        assertEquals(1, SharedLayoutScope.configurationProfileId(3, true))
        assertEquals(1, SharedLayoutScope.configurationProfileId(1, true))
        assertFalse(SharedLayoutScope.canEditConfiguration(3, true))
        assertTrue(SharedLayoutScope.canEditConfiguration(1, true))
    }


    @Test fun everyCustomerSecondaryReadsMainLayoutAndCannotEditIt() {
        for (profileId in 2..6) {
            assertEquals(SharedLayoutScope.MAIN_PROFILE_ID, SharedLayoutScope.configurationProfileId(profileId, true))
            assertFalse(SharedLayoutScope.canEditConfiguration(profileId, true))
        }
    }

    @Test fun familySecondariesRetainTheirOwnLayouts() {
        for (profileId in 2..6) {
            assertEquals(profileId, SharedLayoutScope.configurationProfileId(profileId, false))
            assertTrue(SharedLayoutScope.canEditConfiguration(profileId, false))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidProfileIdIsRejected() {
        SharedLayoutScope.configurationProfileId(0, true)
    }
}
