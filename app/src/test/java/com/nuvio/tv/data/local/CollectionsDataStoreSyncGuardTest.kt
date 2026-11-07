package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
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

    private fun fixture(
        first: TestPreferencesStore,
        second: TestPreferencesStore
    ): Pair<CollectionsDataStore, MutableStateFlow<Int>> {
        val selected = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns selected
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            if (firstArg<Int>() == 1) first else second
        }
        return CollectionsDataStore(mockk<Context>(relaxed = true), factory, profileManager) to selected
    }
}
