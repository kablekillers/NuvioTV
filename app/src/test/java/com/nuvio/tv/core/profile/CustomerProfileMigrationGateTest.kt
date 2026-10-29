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
        persisted: Boolean = true,
        isolated: Boolean = true,
        layoutVerified: Boolean = true,
        deletionDisabled: Boolean = true,
        historyPreserved: Boolean = true,
        recoveryClear: Boolean = true
    ) = CustomerProfileMigrationGate.evaluate(
        mode, verified, approved, audited, cloud, local,
        migrationStatePersisted = persisted,
        personalStateIsolationVerified = isolated,
        sharedMainLayoutVerified = layoutVerified,
        cloudProfileDeletionDisabled = deletionDisabled,
        historicalCloudProfilesPreserved = historyPreserved,
        recoveryJournalClear = recoveryClear
    )

    @Test fun allowsOnlyVerifiedAuditedNonCollidingCustomerMigration() {
        assertTrue(check().mayActivate)
    }

    @Test fun blocksUnresolvedMigrationRecovery() {
        assertFalse(check(recoveryClear = false).mayActivate)
    }

    @Test fun defaultsToBlockingWhenRecoveryStatusIsNotProvided() {
        val decision = CustomerProfileMigrationGate.evaluate(
            mode = AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
            assignmentVerified = true,
            migrationApproved = true,
            syncAuditComplete = true,
            existingCloudProfileIds = listOf(1, 2),
            existingDeviceProfileIds = listOf(1, 3),
            migrationStatePersisted = true,
            personalStateIsolationVerified = true,
            sharedMainLayoutVerified = true,
            cloudProfileDeletionDisabled = true,
            historicalCloudProfilesPreserved = true
        )
        assertFalse(decision.mayActivate)
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

    @Test fun blocksActivationWithoutPrivacyAndSharedLayoutVerification() {
        assertFalse(check(isolated = false).mayActivate)
        assertFalse(check(layoutVerified = false).mayActivate)
        assertFalse(check(deletionDisabled = false).mayActivate)
        assertFalse(check(historyPreserved = false).mayActivate)
    }

    @Test fun invalidIdsReturnBlockedDecisionRatherThanThrowing() {
        assertFalse(check(cloud = listOf(1, -2)).mayActivate)
        assertFalse(check(local = listOf(1, 0)).mayActivate)
    }

    @Test fun blocksDuplicateIdentifiersBeforeMigration() {
        assertFalse(check(cloud = listOf(1, 2, 2)).mayActivate)
        assertFalse(check(local = listOf(1, 4, 4)).mayActivate)
    }

    @Test fun blocksMigrationWithoutMainProfileOnBothSides() {
        assertFalse(check(cloud = listOf(2, 3)).mayActivate)
        assertFalse(check(local = listOf(4, 5)).mayActivate)
    }

    @Test fun blocksMoreThanFivePersonalProfiles() {
        assertFalse(check(local = listOf(1, 4, 5, 6, 7, 8, 9)).mayActivate)
    }

    @Test fun rejectsInvalidConfiguredProfileLimits() {
        for (limit in listOf(-1, 0, 6, 100)) {
            assertFalse(CustomerProfileMigrationGate.evaluate(
                mode = AccountProfileMode.CUSTOMER_DEVICE_LOCAL,
                assignmentVerified = true,
                migrationApproved = true,
                syncAuditComplete = true,
                existingCloudProfileIds = listOf(1, 2),
                existingDeviceProfileIds = listOf(1, 3),
                localProfileCountLimit = limit,
                migrationStatePersisted = true,
                personalStateIsolationVerified = true,
                sharedMainLayoutVerified = true,
                cloudProfileDeletionDisabled = true,
                historicalCloudProfilesPreserved = true
            ).mayActivate)
        }
    }

    @Test fun blocksCloudLocalIdCollisions() {
        assertFalse(check(local = listOf(1, 2, 4)).mayActivate)
    }
}
