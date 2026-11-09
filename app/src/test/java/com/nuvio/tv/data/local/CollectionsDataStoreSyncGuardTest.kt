package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.AuthenticatedProfileModeProvider
import com.nuvio.tv.core.profile.AccountProfileMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsDataStoreSyncGuardTest {
    private val key = stringPreferencesKey("collections_json")

    @Test fun staleAccountCannotReplaceExistingCollectionStorage() = runTest {
        val first = TestPreferencesStore()
        first.edit { it[key] = "[{\"existing\":true}]" }
        val (dataStore, _) = fixture(first, TestPreferencesStore())
        assertTrue(runCatching {
            dataStore.setCollections(
                emptyList(),
                profileId = 1,
                validateBeforeMutation = { error("Account switched") }
            )
        }.isFailure)
        assertEquals("[{\"existing\":true}]", first.value[key])
    }

    @Test fun explicitProfileIdPreventsWriteToNewlySelectedProfile() = runTest {
        val first = TestPreferencesStore()
        val second = TestPreferencesStore()
        second.edit { it[key] = "[{\"keep\":true}]" }
        val (dataStore, selected) = fixture(first, second)
        selected.value = 2
        dataStore.setCollections(
            emptyList(),
            profileId = 1,
            validateBeforeMutation = {}
        )
        assertEquals("[{\"keep\":true}]", second.value[key])
        assertEquals(null, first.value[key])
    }


    @Test fun customerSecondaryReadsMainCollections() = runTest {
        val main = TestPreferencesStore()
        val secondary = TestPreferencesStore()
        main.edit { it[key] = """[{"id":"main","title":"Shared","folders":[]}]""" }
        val (dataStore, selected) = fixture(main, secondary, AccountProfileMode.CUSTOMER_DEVICE_LOCAL)
        selected.value = 2
        assertEquals("main", dataStore.getCurrentCollections().single().id)
        assertEquals(null, secondary.value[key])
    }

    @Test fun customerSecondaryCannotOverwriteMainCollections() = runTest {
        val main = TestPreferencesStore()
        main.edit { it[key] = """[{"id":"main","title":"Shared","folders":[]}]""" }
        val (dataStore, selected) = fixture(main, TestPreferencesStore(), AccountProfileMode.CUSTOMER_DEVICE_LOCAL)
        selected.value = 2
        assertTrue(runCatching { dataStore.setCollections(emptyList()) }.isFailure)
        assertEquals("main", dataStore.getCurrentCollections().single().id)
    }


    @Test fun changesToMainAppearForExistingSecondaryWithoutCopy() = runTest {
        val main = TestPreferencesStore()
        val secondary = TestPreferencesStore()
        val (dataStore, selected) = fixture(main, secondary, AccountProfileMode.CUSTOMER_DEVICE_LOCAL)
        selected.value = 1
        dataStore.setCollections(
            listOf(com.nuvio.tv.domain.model.Collection(id = "first", title = "First"))
        )
        selected.value = 3
        assertEquals("first", dataStore.getCurrentCollections().single().id)
        selected.value = 1
        dataStore.setCollections(
            listOf(com.nuvio.tv.domain.model.Collection(id = "updated", title = "Updated"))
        )
        selected.value = 3
        assertEquals("updated", dataStore.getCurrentCollections().single().id)
        assertEquals(null, secondary.value[key])
    }

    private fun fixture(
        first: TestPreferencesStore,
        second: TestPreferencesStore,
        mode: AccountProfileMode = AccountProfileMode.LEGACY_CLOUD
    ): Pair<CollectionsDataStore, MutableStateFlow<Int>> {
        val selected = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns selected
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            if (firstArg<Int>() == 1) first else second
        }
        val modeProvider = mockk<AuthenticatedProfileModeProvider>()
        every { modeProvider.currentMode() } returns mode
        return CollectionsDataStore(
            mockk<Context>(relaxed = true), factory, profileManager, modeProvider
        ) to selected
    }
}
