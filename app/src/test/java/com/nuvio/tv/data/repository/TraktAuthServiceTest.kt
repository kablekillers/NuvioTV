package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class TraktAuthServiceTest {
    @Test
    fun `disabled Trakt integration never refreshes credentials or contacts Trakt`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.hasRequiredCredentials())
        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 0) { traktApi.refreshToken(any()) }
        coVerify(exactly = 0) { traktAuthDataStore.clearAuth() }
        coVerify(exactly = 0) { authSessionNoticeDataStore.markTraktReconnectRequired() }
    }
}
