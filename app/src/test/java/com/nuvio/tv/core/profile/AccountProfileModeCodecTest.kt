package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountProfileModeCodecTest {
    @Test fun missingOrUnknownModePreservesExistingCloudProfiles() {
        assertEquals(AccountProfileMode.LEGACY_CLOUD, AccountProfileModeCodec.decode(null))
        assertEquals(AccountProfileMode.LEGACY_CLOUD, AccountProfileModeCodec.decode("unknown"))
    }

    @Test fun familyModeRemainsCloudSynced() {
        assertEquals(
            AccountProfileMode.FAMILY_CLOUD,
            AccountProfileModeCodec.effectiveMode("family_cloud", true, false)
        )
    }

    @Test fun unverifiedFamilyAssignmentFallsBackToLegacy() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeCodec.effectiveMode("family_cloud", false, false)
        )
    }

    @Test fun customerModeRequiresVerifiedAccountAndEnabledMigration() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeCodec.effectiveMode("customer_device_local", false, true)
        )
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeCodec.effectiveMode("customer_device_local", true, false)
        )
        assertEquals(
            AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
            AccountProfileModeCodec.effectiveMode("customer_device_local", true, true)
        )
    }

    @Test fun encodingRoundTrips() {
        AccountProfileMode.values().forEach { mode ->
            assertEquals(mode, AccountProfileModeCodec.decode(AccountProfileModeCodec.encode(mode)))
        }
    }
}
