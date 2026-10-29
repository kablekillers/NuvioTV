package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountModeStorageKeysTest {
    @Test fun sameVerifiedAccountGetsStableKeys() {
        assertEquals(
            AccountModeStorageKeys.forVerifiedAccount("customer-account"),
            AccountModeStorageKeys.forVerifiedAccount("customer-account")
        )
    }

    @Test fun customerAndFamilyKeysNeverOverlap() {
        val customer = AccountModeStorageKeys.forVerifiedAccount("customer-account")
        val family = AccountModeStorageKeys.forVerifiedAccount("family-account")
        assertNotEquals(customer.verifiedId, family.verifiedId)
        assertNotEquals(customer.mode, family.mode)
        assertNotEquals(customer.migrationComplete, family.migrationComplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankAccountIsRejected() {
        AccountModeStorageKeys.forVerifiedAccount(" ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun paddedAccountIsRejected() {
        AccountModeStorageKeys.forVerifiedAccount(" family-account ")
    }
}
