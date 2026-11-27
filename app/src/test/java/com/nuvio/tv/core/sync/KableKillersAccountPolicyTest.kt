package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KableKillersAccountPolicyTest {
    @Test
    fun customerAccountKeepsProfilesLocal() {
        assertFalse(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.FullAccount("u1", "kablekillers6@gmail.com")))
        assertFalse(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.FullAccount("u1", " KableKillers6@Gmail.com ")))
    }

    @Test
    fun personalAccountUsesCloudProfiles() {
        assertTrue(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.FullAccount("u2", "millertime42085@gmail.com")))
    }

    @Test
    fun unknownStatesStayLocal() {
        assertFalse(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.SignedOut))
        assertFalse(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.Loading))
        assertFalse(KableKillersAccountPolicy.cloudProfilesEnabled(AuthState.FullAccount("u3", "")))
    }

    private val customer = AuthState.FullAccount("u1", "kablekillers6@gmail.com")
    private val owner = AuthState.FullAccount("u2", "millertime42085@gmail.com")

    @Test
    fun customerProfileOneIsLockedUntilCloudConfirms() {
        assertEquals(
            mapOf(1 to true),
            KableKillersAccountPolicy.enforcedPinLocks(customer, emptyMap(), cloudLocksConfirmed = false)
        )
    }

    @Test
    fun customerProfileOneFollowsCloudOnceConfirmed() {
        assertEquals(mapOf(1 to true), KableKillersAccountPolicy.enforcedPinLocks(customer, mapOf(1 to true), true))
        assertEquals(mapOf(1 to false), KableKillersAccountPolicy.enforcedPinLocks(customer, mapOf(1 to false), true))
        assertEquals(mapOf(1 to false), KableKillersAccountPolicy.enforcedPinLocks(customer, emptyMap(), true))
    }

    @Test
    fun customerHouseholdProfilesIgnoreCloudPins() {
        val enforced = KableKillersAccountPolicy.enforcedPinLocks(customer, mapOf(1 to true, 2 to true, 3 to true), true)
        assertEquals(mapOf(1 to true), enforced)
    }

    @Test
    fun ownerAccountUsesCloudPinsAsIs() {
        val locks = mapOf(1 to true, 2 to true)
        assertEquals(locks, KableKillersAccountPolicy.enforcedPinLocks(owner, locks, false))
        assertEquals(emptyMap<Int, Boolean>(), KableKillersAccountPolicy.enforcedPinLocks(owner, emptyMap(), false))
    }

    @Test
    fun customerAccountDetection() {
        assertTrue(KableKillersAccountPolicy.isCustomerAccount(customer))
        assertFalse(KableKillersAccountPolicy.isCustomerAccount(owner))
        assertFalse(KableKillersAccountPolicy.isCustomerAccount(AuthState.SignedOut))
    }

    @Test
    fun customerHouseholdProfilesUseDevicePins() {
        val enforced = KableKillersAccountPolicy.enforcedPinLocks(
            customer,
            cloudLocks = mapOf(1 to true, 2 to true),
            cloudLocksConfirmed = true,
            householdLocks = mapOf(3 to true, 1 to false)
        )
        // Cloud PIN on 2 ignored, device PIN on 3 kept, device map can't unlock Profile 1.
        assertEquals(mapOf(1 to true, 3 to true), enforced)
    }

    @Test
    fun devicePinsOnlyForCustomerHouseholdProfiles() {
        assertTrue(KableKillersAccountPolicy.usesDevicePin(customer, 2))
        assertFalse(KableKillersAccountPolicy.usesDevicePin(customer, 1))
        assertFalse(KableKillersAccountPolicy.usesDevicePin(owner, 2))
    }

    @Test
    fun customerWatchDataStaysOnDeviceIncludingProfileOne() {
        (1..6).forEach { assertFalse(KableKillersAccountPolicy.syncsWatchDataToCloud(customer, it)) }
    }

    @Test
    fun ownerLoginSyncsEveryProfilesWatchData() {
        (1..6).forEach { assertTrue(KableKillersAccountPolicy.syncsWatchDataToCloud(owner, it)) }
    }
}
