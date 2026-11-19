package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.AuthState
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
}
