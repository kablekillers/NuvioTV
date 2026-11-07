package com.nuvio.tv.core.sync

import com.nuvio.tv.core.profile.AuthenticatedProfileModeProvider
import com.nuvio.tv.core.profile.AccountProfilePolicy

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileSyncBoundary
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseCollectionBlob
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionSyncService"

@Singleton
class CollectionSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val accountProfileModeProvider: AuthenticatedProfileModeProvider,
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    // Keep disabled until device-local profile migration is fully wired.
    private val deviceLocalProfilesEnabled: Boolean
        get() = AccountProfilePolicy.usesDeviceOnlySecondaries(accountProfileModeProvider.currentMode())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    private fun captureSyncIdentity(profileId: Int):
        Pair<com.nuvio.tv.core.profile.ProfileSyncIdentityFence, com.nuvio.tv.core.profile.ProfileSyncIdentityFence.Snapshot> {
        val fence = com.nuvio.tv.core.profile.ProfileSyncIdentityFence(
            { authManager.verifiedProfileAccountId },
            { accountProfileModeProvider.currentMode() }
        )
        val identity = fence.capture()
        check(profileManager.activeProfileId.value == profileId) {
            "Collections sync profile changed before request"
        }
        return fence to identity
    }

    private fun requireSyncIdentity(
        profileId: Int,
        fence: com.nuvio.tv.core.profile.ProfileSyncIdentityFence,
        identity: com.nuvio.tv.core.profile.ProfileSyncIdentityFence.Snapshot
    ) {
        fence.requireCurrent(identity)
        check(profileManager.activeProfileId.value == profileId) {
            "Collections sync discarded after active profile change"
        }
        check(ProfileSyncBoundary.maySyncPersonalViewingState(profileId,
            AccountProfilePolicy.usesDeviceOnlySecondaries(accountProfileModeProvider.currentMode()))) {
            "Collections sync no longer permitted for this profile"
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Push local collections JSON to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            if (!ProfileSyncBoundary.maySyncPersonalViewingState(profileId, deviceLocalProfilesEnabled)) {
                return@withContext Result.success(Unit)
            }
            val (fence, identity) = captureSyncIdentity(profileId)
            val json = collectionsDataStore.exportCurrentProfileJson()
            requireSyncIdentity(profileId, fence, identity)

            val collectionsJsonElement = if (json.isNullOrBlank()) {
                JsonArray(emptyList())
            } else {
                Json.parseToJsonElement(json)
            }

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", collectionsJsonElement)
                putSyncOriginClientId(syncClientIdentity)
            }

            requireSyncIdentity(profileId, fence, identity)
            withJwtRefreshRetry {
                requireSyncIdentity(profileId, fence, identity)
                postgrest.rpc("sync_push_collections", params)
            }
            requireSyncIdentity(profileId, fence, identity)

            Log.d(TAG, "Pushed collections to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push collections to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull remote collections JSON and apply locally.
     * Returns true if local state was updated.
     */
    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            if (!ProfileSyncBoundary.maySyncPersonalViewingState(profileId, deviceLocalProfilesEnabled)) {
                return@withContext Result.success(false)
            }

            val (fence, identity) = captureSyncIdentity(profileId)
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }

            val response = withJwtRefreshRetry {
                requireSyncIdentity(profileId, fence, identity)
                postgrest.rpc("sync_pull_collections", params)
            }
            requireSyncIdentity(profileId, fence, identity)
            val rows = response.decodeList<SupabaseCollectionBlob>()
            val blob = rows.firstOrNull()
            if (blob == null) {
                Log.d(TAG, "No remote collections for profile $profileId; keeping local")
                return@withContext Result.success(false)
            }

            val remoteJson = blob.collectionsJson.toString()
            requireSyncIdentity(profileId, fence, identity)
            val remoteCollections = collectionsDataStore.importFromJson(remoteJson)
            requireSyncIdentity(profileId, fence, identity)

            // Preserve local if remote is empty but local has data
            val localCollections = collectionsDataStore.getCurrentCollections()
            if (remoteCollections.isEmpty() && localCollections.isNotEmpty()) {
                Log.w(TAG, "Remote collections empty while local has ${localCollections.size}; preserving local")
                return@withContext Result.success(false)
            }

            // Check if different
            val localJson = collectionsDataStore.exportCurrentProfileJson() ?: ""
            if (remoteJson == localJson) {
                Log.d(TAG, "Remote collections already match local for profile $profileId")
                return@withContext Result.success(false)
            }

            requireSyncIdentity(profileId, fence, identity)
            isSyncingFromRemote = true
            try {
                requireSyncIdentity(profileId, fence, identity)
                collectionsDataStore.setCollections(
                    remoteCollections,
                    profileId = profileId,
                    validateBeforeMutation = { requireSyncIdentity(profileId, fence, identity) }
                )
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Applied ${remoteCollections.size} remote collections for profile $profileId")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull collections from remote", e)
            Result.failure(e)
        }
    }

    /**
     * Trigger a debounced push to remote after a local change.
     */
    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }
}
