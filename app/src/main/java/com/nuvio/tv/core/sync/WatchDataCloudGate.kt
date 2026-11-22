package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import javax.inject.Inject
import javax.inject.Singleton

/** Decides whether a profile's watch history and Library may use the cloud. */
@Singleton
open class WatchDataCloudGate @Inject constructor(
    private val authManager: AuthManager
) {
    open fun usesCloud(profileId: Int): Boolean =
        KableKillersAccountPolicy.syncsWatchDataToCloud(authManager.authState.value, profileId)
}
