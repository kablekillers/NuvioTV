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

    @Test fun localModeBlocksAllMetadataAndPersonalViewingSync() {
        assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(1, true))
        assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(2, true))
        assertFalse(ProfileSyncBoundary.maySyncPersonalViewingState(1, true))
        assertFalse(ProfileSyncBoundary.maySyncPersonalViewingState(2, true))
        assertEquals(emptyList<Int>(), ProfileSyncBoundary.visibleRemoteProfileIds(listOf(1, 2, 3), true))
    }

    @Test fun sharedAddonSyncRemainsEnabledInBothModes() {
        assertTrue(ProfileSyncBoundary.maySyncSharedAddonConfiguration(false))
        assertTrue(ProfileSyncBoundary.maySyncSharedAddonConfiguration(true))
    }

    @Test fun customerAccountBlocksPersonalSyncForMainAndAllSecondaries() {
        val mode = AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        for (profileId in 1..6) {
            assertFalse(ProfileSyncBoundary.maySyncPersonalViewingState(profileId, mode))
        }
        assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(1, mode))
        for (profileId in 2..6) {
            assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(profileId, mode))
        }
        assertEquals(
            emptyList<Int>(),
            ProfileSyncBoundary.visibleRemoteProfileIds(listOf(1, 2, 3, 4, 5, 6), mode)
        )
    }

    @Test fun familyAndUnmigratedAccountsRetainAllExistingCloudSync() {
        for (mode in listOf(AccountProfileMode.FAMILY_CLOUD, AccountProfileMode.LEGACY_CLOUD)) {
            for (profileId in 1..6) {
                assertTrue(ProfileSyncBoundary.maySyncPersonalViewingState(profileId, mode))
                assertTrue(ProfileSyncBoundary.maySyncProfileMetadata(profileId, mode))
            }
            assertEquals(
                listOf(1, 2, 3, 4, 5, 6),
                ProfileSyncBoundary.visibleRemoteProfileIds(listOf(1, 2, 3, 4, 5, 6), mode)
            )
        }
    }

}
