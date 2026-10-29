package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileTransitionPolicyTest {
    @Test fun switchingToCustomerModeAlwaysRequiresMigration() {
        assertEquals(
            AccountProfileTransitionPolicy.Action.REQUIRE_CUSTOMER_MIGRATION,
            AccountProfileTransitionPolicy.plan(
                AccountProfileMode.LEGACY_CLOUD,
                AccountProfileMode.CUSTOMER_DEVICE_LOCAL
            )
        )
        assertEquals(
            AccountProfileTransitionPolicy.Action.REQUIRE_CUSTOMER_MIGRATION,
            AccountProfileTransitionPolicy.plan(
                AccountProfileMode.FAMILY_CLOUD,
                AccountProfileMode.CUSTOMER_DEVICE_LOCAL
            )
        )
        assertFalse(AccountProfileTransitionPolicy.mayApplyWithoutMigration(
            AccountProfileMode.FAMILY_CLOUD, AccountProfileMode.CUSTOMER_DEVICE_LOCAL
        ))
    }

    @Test fun familyCloudSelectionDoesNotRequireDestructiveMigration() {
        assertEquals(
            AccountProfileTransitionPolicy.Action.APPLY_CLOUD_MODE,
            AccountProfileTransitionPolicy.plan(
                AccountProfileMode.LEGACY_CLOUD, AccountProfileMode.FAMILY_CLOUD
            )
        )
        assertTrue(AccountProfileTransitionPolicy.mayApplyWithoutMigration(
            AccountProfileMode.LEGACY_CLOUD, AccountProfileMode.FAMILY_CLOUD
        ))
    }

    @Test fun noOpModeSelectionKeepsCurrentState() {
        assertEquals(
            AccountProfileTransitionPolicy.Action.KEEP_CURRENT,
            AccountProfileTransitionPolicy.plan(
                AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
                AccountProfileMode.CUSTOMER_DEVICE_LOCAL
            )
        )
    }
}
