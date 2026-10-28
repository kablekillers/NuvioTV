package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfilePolicyTest {
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
}
