package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceLocalProfilesFeatureTest {
    @Test fun migrationRemainsDisabledUntilApproved() {
        assertFalse(DeviceLocalProfilesFeature.ENABLED)
    }
}
