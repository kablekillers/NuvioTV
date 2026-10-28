package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerProfileMigrationGateTest {
    private fun check(
        mode: AccountProfileMode = AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
        verified: Boolean = true,
        approved: Boolean = true,
        audited: Boolean = true,
        cloud: List<Int> = listOf(1, 2, 3),
        local: List<Int> = listOf(1, 4, 5)
    ) = CustomerProfileMigrationGate.evaluate(mode, verified, approved, audited, cloud, local)

    @Test fun allowsOnlyVerifiedAuditedNonCollidingCustomerMigration() {
        assertTrue(check().mayActivate)
    }

    @Test fun blocksFamilyAccountConversion() {
        assertFalse(check(mode = AccountProfileMode.FAMILY_CLOUD).mayActivate)
    }

    @Test fun blocksUnverifiedOrUnapprovedMigration() {
        assertFalse(check(verified = false).mayActivate)
        assertFalse(check(approved = false).mayActivate)
    }

    @Test fun blocksMigrationUntilSyncAuditIsComplete() {
        assertFalse(check(audited = false).mayActivate)
    }

    @Test fun blocksCloudLocalIdCollisions() {
        assertFalse(check(local = listOf(1, 2, 4)).mayActivate)
    }
}
