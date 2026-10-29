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

    @Test(expected = IllegalArgumentException::class)
    fun invalidProfileIdIsRejected() {
        SharedLayoutScope.configurationProfileId(0, true)
    }
}
