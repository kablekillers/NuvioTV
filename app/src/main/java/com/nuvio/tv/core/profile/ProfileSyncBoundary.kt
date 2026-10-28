package com.nuvio.tv.core.profile

/**
 * Gate used by sync services when device-local profile mode is enabled.
 *
 * Existing accounts remain in legacy mode until the complete migration and
 * viewing-state isolation are implemented. Never switch this on piecemeal.
 */
internal object ProfileSyncBoundary {
    // Account-aware overloads for the upcoming authentication integration.
    // Family accounts must retain full cloud synchronization.
    fun maySyncProfileMetadata(profileId: Int, mode: AccountProfileMode): Boolean =
        AccountProfilePolicy.maySyncProfileMetadata(profileId, mode)

    fun maySyncPersonalViewingState(profileId: Int, mode: AccountProfileMode): Boolean =
        AccountProfilePolicy.maySyncViewingState(profileId, mode)

    fun visibleRemoteProfileIds(
        remoteProfileIds: List<Int>,
        mode: AccountProfileMode
    ): List<Int> = AccountProfilePolicy.visibleRemoteProfileIds(remoteProfileIds, mode)

    fun maySyncProfileMetadata(profileId: Int, deviceLocalModeEnabled: Boolean): Boolean =
        !deviceLocalModeEnabled || profileId == 1

    fun maySyncPersonalViewingState(profileId: Int, deviceLocalModeEnabled: Boolean): Boolean =
        !deviceLocalModeEnabled || profileId == 1

    fun maySyncSharedAddonConfiguration(deviceLocalModeEnabled: Boolean): Boolean = true

    fun visibleRemoteProfileIds(
        remoteProfileIds: List<Int>,
        deviceLocalModeEnabled: Boolean
    ): List<Int> = if (deviceLocalModeEnabled) remoteProfileIds.filter { it == 1 } else remoteProfileIds
}
