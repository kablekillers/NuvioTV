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
        local: List<Int> = listOf(1, 4, 5),
        persisted: Boolean = true
    ) = CustomerProfileMigrationGate.evaluate(
        mode, verified, approved, audited, cloud, local, migrationStatePersisted = persisted
    )

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

    @Test fun blocksActivationWithoutDurableMigrationRecord() {
        assertFalse(check(persisted = false).mayActivate)
    }

    @Test fun blocksMigrationUntilSyncAuditIsComplete() {
        assertFalse(check(audited = false).mayActivate)
    }

    @Test fun blocksMigrationWithoutMainProfileOnBothSides() {
        assertFalse(check(cloud = listOf(2, 3)).mayActivate)
        assertFalse(check(local = listOf(4, 5)).mayActivate)
    }

    @Test fun blocksMoreThanFivePersonalProfiles() {
        assertFalse(check(local = listOf(1, 4, 5, 6, 7, 8, 9)).mayActivate)
    }

    @Test fun blocksCloudLocalIdCollisions() {
        assertFalse(check(local = listOf(1, 2, 4)).mayActivate)
    }
}
