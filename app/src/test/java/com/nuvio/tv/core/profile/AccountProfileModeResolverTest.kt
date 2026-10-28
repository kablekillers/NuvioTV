package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountProfileModeResolverTest {
    @Test fun sameAccountMayUseVerifiedCustomerMode() {
        assertEquals(
            AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
            AccountProfileModeResolver.resolve("customer-account", "customer-account", "customer_device_local", true)
        )
    }

    @Test fun switchingToFamilyAccountCannotInheritCustomerMode() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve("family-account", "customer-account", "customer_device_local", true)
        )
    }

    @Test fun missingIdentityCannotActivateCustomerMode() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve(null, "customer-account", "customer_device_local", true)
        )
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve("", "", "customer_device_local", true)
        )
    }

    @Test fun familyAssignmentAlsoCannotLeakToDifferentLogin() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve("customer-account", "family-account", "family_cloud", true)
        )
        assertEquals(
            AccountProfileMode.FAMILY_CLOUD,
            AccountProfileModeResolver.resolve("family-account", "family-account", "family_cloud", false)
        )
    }

    @Test fun whitespacePaddedIdentityCannotActivateCustomerMode() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve(" customer-account ", " customer-account ", "customer_device_local", true)
        )
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve("customer-account", "customer-account ", "customer_device_local", true)
        )
    }

    @Test fun migrationGateOverridesMatchingCustomerAssignment() {
        assertEquals(
            AccountProfileMode.LEGACY_CLOUD,
            AccountProfileModeResolver.resolve("customer-account", "customer-account", "customer_device_local", false)
        )
    }
}
