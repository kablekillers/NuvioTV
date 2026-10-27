package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncBoundaryTest {
    @Test fun legacyModePreservesExistingSync() {
        assertTrue(ProfileSyncBoundary.maySyncProfileMetadata(2, false))
        assertTrue(ProfileSyncBoundary.maySyncPersonalViewingState(2, false))
        assertEquals(listOf(1, 2, 3), ProfileSyncBoundary.visibleRemoteProfileIds(listOf(1, 2, 3), false))
    }

    @Test fun localModeAllowsOnlyMainProfileRemoteState() {
        assertTrue(ProfileSyncBoundary.maySyncProfileMetadata(1, true))
        assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(2, true))
        assertFalse(ProfileSyncBoundary.maySyncPersonalViewingState(2, true))
        assertEquals(listOf(1), ProfileSyncBoundary.visibleRemoteProfileIds(listOf(1, 2, 3), true))
    }

    @Test fun sharedAddonSyncRemainsEnabledInBothModes() {
        assertTrue(ProfileSyncBoundary.maySyncSharedAddonConfiguration(false))
        assertTrue(ProfileSyncBoundary.maySyncSharedAddonConfiguration(true))
    }
}
