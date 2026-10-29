package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDataScopeTest {
    @Test fun addonAndPluginConfigurationAlwaysBelongsToSharedAccount() {
        listOf(
            ProfileDataKind.ADDONS,
            ProfileDataKind.PLUGINS,
            ProfileDataKind.ADDON_SETTINGS,
            ProfileDataKind.CATALOG_CONFIGURATION
        ).forEach { kind ->
            assertEquals(ProfileDataScope.SharedAccount, ProfileDataPolicy.scopeFor(kind, null))
            assertEquals(ProfileDataScope.SharedAccount, ProfileDataPolicy.scopeFor(kind, "household-a"))
            assertTrue(ProfileDataPolicy.canUploadToSharedAccount(kind, "household-a"))
        }
    }

    @Test fun personalViewingStateIsIsolatedByLocalProfileId() {
        listOf(
            ProfileDataKind.CONTINUE_WATCHING,
            ProfileDataKind.WATCH_HISTORY,
            ProfileDataKind.FAVORITES,
            ProfileDataKind.PLAYBACK_PROGRESS
        ).forEach { kind ->
            assertEquals(ProfileDataScope.LocalProfile("a"), ProfileDataPolicy.scopeFor(kind, "a"))
            assertEquals(ProfileDataScope.LocalProfile("b"), ProfileDataPolicy.scopeFor(kind, "b"))
            assertFalse(ProfileDataPolicy.canUploadToSharedAccount(kind, "a"))
            assertEquals(ProfileDataScope.SharedAccount, ProfileDataPolicy.scopeFor(kind, null))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankLocalProfileIdIsRejected() {
        ProfileDataPolicy.scopeFor(ProfileDataKind.FAVORITES, " ")
    }
}
