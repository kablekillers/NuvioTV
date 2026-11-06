package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ProfileSyncIdentityFenceTest {
    @Test fun identicalVerifiedAccountAndModeRemainValid() {
        val account = AtomicReference<String?>("account-a")
        val mode = AtomicReference(AccountProfileMode.LEGACY_CLOUD)
        val fence = ProfileSyncIdentityFence({ account.get() }, { mode.get() })
        val snapshot = fence.capture()
        assertTrue(fence.isCurrent(snapshot))
        fence.requireCurrent(snapshot)
    }

    @Test fun rejectsSwitchToAnotherAccountEvenWhenModeStaysSame() {
        val account = AtomicReference<String?>("account-a")
        val fence = ProfileSyncIdentityFence(
            { account.get() }, { AccountProfileMode.LEGACY_CLOUD }
        )
        val snapshot = fence.capture()
        account.set("account-b")
        assertFalse(fence.isCurrent(snapshot))
        assertTrue(runCatching { fence.requireCurrent(snapshot) }.isFailure)
    }

    @Test fun rejectsLogoutAndChangeOfResolvedProfileMode() {
        val account = AtomicReference<String?>("account-a")
        val mode = AtomicReference(AccountProfileMode.LEGACY_CLOUD)
        val fence = ProfileSyncIdentityFence({ account.get() }, { mode.get() })
        val snapshot = fence.capture()
        mode.set(AccountProfileMode.CUSTOMER_DEVICE_LOCAL)
        assertFalse(fence.isCurrent(snapshot))
        mode.set(AccountProfileMode.LEGACY_CLOUD)
        account.set(null)
        assertFalse(fence.isCurrent(snapshot))
    }

    @Test fun invalidAccountDoesNotStartPersonalSyncSnapshot() {
        val fence = ProfileSyncIdentityFence(
            { " " }, { AccountProfileMode.LEGACY_CLOUD }
        )
        assertTrue(runCatching { fence.capture() }.isFailure)
    }

    @Test fun changingIdentityDuringCaptureFailsClosed() {
        var reads = 0
        val fence = ProfileSyncIdentityFence(
            { if (++reads == 1) "account-a" else "account-b" },
            { AccountProfileMode.LEGACY_CLOUD }
        )
        assertTrue(runCatching { fence.capture() }.isFailure)
    }
}
