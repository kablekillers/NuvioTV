package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfilePolicyTest {
    @Test fun customerModeNeverUploadsPersonalViewingStateEvenForMainProfile() {
        // Every customer uses the same authenticated login. No profile index,
        // including Main (1), is safe for account-wide viewing-state sync.
        val customer = AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        for (profileId in 1..100) {
            assertFalse(AccountProfilePolicy.maySyncViewingState(profileId, customer))
            assertFalse(ProfileSyncBoundary.maySyncPersonalViewingState(profileId, customer))
        }
    }

    @Test fun customerModeNeverImportsOtherCustomersSecondaryProfiles() {
        val customer = AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        val remoteProfiles = (1..100).toList()
        assertEquals(listOf(1), ProfileSyncBoundary.visibleRemoteProfileIds(remoteProfiles, customer))
        for (profileId in 2..100) {
            assertFalse(ProfileSyncBoundary.maySyncProfileMetadata(profileId, customer))
        }
    }

    @Test fun customerModeBlocksAllAccountWideProfileDeletesAndPinOperations() {
        assertFalse(AccountProfilePolicy.mayDeleteRemoteProfileData(AccountProfileMode.CUSTOMER_DEVICE_LOCAL))
        assertFalse(AccountProfilePolicy.maySyncRemoteProfilePins(AccountProfileMode.CUSTOMER_DEVICE_LOCAL))
        for (mode in listOf(AccountProfileMode.LEGACY_CLOUD, AccountProfileMode.FAMILY_CLOUD)) {
            assertTrue(AccountProfilePolicy.mayDeleteRemoteProfileData(mode))
            assertTrue(AccountProfilePolicy.maySyncRemoteProfilePins(mode))
        }
    }

    @Test fun familyProfilesStayCloudSynced() {
        val mode = AccountProfileMode.FAMILY_CLOUD
        assertFalse(AccountProfilePolicy.usesDeviceOnlySecondaries(mode))
        assertTrue(AccountProfilePolicy.maySyncProfileMetadata(3, mode))
        assertTrue(AccountProfilePolicy.maySyncViewingState(3, mode))
        assertEquals(listOf(1, 2, 3), AccountProfilePolicy.visibleRemoteProfileIds(listOf(1, 2, 3), mode))
    }

    @Test fun customerProfilesAreDeviceOnly() {
        val mode = AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        assertTrue(AccountProfilePolicy.usesDeviceOnlySecondaries(mode))
        assertFalse(AccountProfilePolicy.maySyncProfileMetadata(3, mode))
        assertFalse(AccountProfilePolicy.maySyncViewingState(3, mode))
        assertTrue(AccountProfilePolicy.maySyncProfileMetadata(1, mode))
        assertFalse(AccountProfilePolicy.maySyncViewingState(1, mode))
        assertEquals(listOf(1), AccountProfilePolicy.visibleRemoteProfileIds(listOf(1, 2, 3), mode))
        assertFalse(AccountProfilePolicy.canEditSharedConfiguration(2, mode))
        assertTrue(AccountProfilePolicy.canEditSharedConfiguration(1, mode))
    }

    @Test fun existingAccountsKeepLegacyCloudBehaviorByDefault() {
        val mode = AccountProfileMode.LEGACY_CLOUD
        assertFalse(AccountProfilePolicy.usesDeviceOnlySecondaries(mode))
        assertTrue(AccountProfilePolicy.maySyncViewingState(2, mode))
        assertEquals(listOf(1, 2), AccountProfilePolicy.visibleRemoteProfileIds(listOf(1, 2), mode))
    }

    @Test fun customerMainIsOnlySharedConfigurationEditor() {
        val customer = AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        for (profileId in 2..6) {
            assertFalse(AccountProfilePolicy.canEditSharedConfiguration(profileId, customer))
            assertFalse(AccountProfilePolicy.maySyncProfileMetadata(profileId, customer))
            assertFalse(AccountProfilePolicy.maySyncViewingState(profileId, customer))
        }
        assertTrue(AccountProfilePolicy.canEditSharedConfiguration(1, customer))
        assertTrue(AccountProfilePolicy.maySyncProfileMetadata(1, customer))
        assertFalse(AccountProfilePolicy.maySyncViewingState(1, customer))
    }

    @Test fun familyAndLegacyModesRetainCloudSyncForAllProfiles() {
        for (mode in listOf(AccountProfileMode.FAMILY_CLOUD, AccountProfileMode.LEGACY_CLOUD)) {
            for (profileId in 1..6) {
                assertTrue(AccountProfilePolicy.canEditSharedConfiguration(profileId, mode))
                assertTrue(AccountProfilePolicy.maySyncProfileMetadata(profileId, mode))
                assertTrue(AccountProfilePolicy.maySyncViewingState(profileId, mode))
            }
        }
    }

    @Test fun remoteSecondaryProfileVisibilityDependsOnVerifiedMode() {
        val remote = listOf(1, 2, 3, 4, 5, 6)
        assertEquals(listOf(1), AccountProfilePolicy.visibleRemoteProfileIds(remote, AccountProfileMode.CUSTOMER_DEVICE_LOCAL))
        assertEquals(remote, AccountProfilePolicy.visibleRemoteProfileIds(remote, AccountProfileMode.FAMILY_CLOUD))
        assertEquals(remote, AccountProfilePolicy.visibleRemoteProfileIds(remote, AccountProfileMode.LEGACY_CLOUD))
    }
}
