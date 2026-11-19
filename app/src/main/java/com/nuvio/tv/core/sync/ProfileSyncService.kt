package com.nuvio.tv.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileLockState
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"
private const val PROFILE_PULL_MIN_INTERVAL_MS = 10_000L

internal data class ProfilePullFreshness(
    val userId: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateUserId: String, nowMs: Long): Boolean {
        return userId == candidateUserId &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < PROFILE_PULL_MIN_INTERVAL_MS
    }
}

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    data class Failure(val throwable: Throwable) : SetProfilePinResult()
}

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity,
    @ApplicationContext private val context: Context
) {
    private val pullMutex = Mutex()

    private fun cloudProfilesEnabled(): Boolean =
        KableKillersAccountPolicy.cloudProfilesEnabled(authManager.authState.value)

    private val seedPrefs by lazy {
        context.getSharedPreferences("kk_cloud_profiles", Context.MODE_PRIVATE)
    }
    private var pullFreshness = ProfilePullFreshness()
    private var lastPulledProfiles = emptyList<UserProfile>()

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    // Shared customer account: household profiles are device-local, so the
    // profile list is never uploaded. Any other account keeps its profiles in
    // the cloud so every device signed in with it shows the same list.
    suspend fun pushToRemote(): Result<Unit> =
        if (cloudProfilesEnabled()) cloudPushToRemote() else Result.success(Unit)

    private suspend fun cloudPushToRemote(
        profilesOverride: List<UserProfile>? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profiles = profilesOverride ?: profileManager.profiles.value

            val params = buildJsonObject {
                put("p_client_max_profiles", ProfileManager.MAX_PROFILES)
                put("p_profiles", buildJsonArray {
                    profiles.forEach { profile ->
                        addJsonObject {
                            put("profile_index", profile.id)
                            put("name", profile.name)
                            put("avatar_color_hex", profile.avatarColorHex)
                            put("uses_primary_addons", profile.usesPrimaryAddons)
                            put("uses_primary_plugins", profile.usesPrimaryPlugins)
                            put("avatar_id", if (profile.avatarUrl.isNullOrBlank()) profile.avatarId else null)
                            put("avatar_url", profile.avatarUrl?.takeIf { it.isNotBlank() })
                            put("profile_background_id", profile.profileBackgroundId)
                            put("profile_background_url", profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }

            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(force: Boolean = false): Result<List<UserProfile>> =
        if (cloudProfilesEnabled()) cloudPullFromRemote(force) else householdPullFromRemote()

    // All customers use the same account, but their household profiles must
    // remain local. Only refresh the master profile's display identity.
    // Never replace the local list with the server's profile list.
    private suspend fun householdPullFromRemote(): Result<List<UserProfile>> =
        withContext(Dispatchers.IO) {
            pullMutex.withLock {
                try {
                    val remote = withJwtRefreshRetry {
                        postgrest.rpc("sync_pull_profiles")
                    }.decodeList<SupabaseProfile>()
                    val primary = remote.firstOrNull { it.profileIndex == 1 }
                    if (primary != null && primary.name.isNotBlank()) {
                        val current = profileManager.profiles.value.firstOrNull { it.id == 1 }
                        if (current != null) {
                            profileDataStore.upsertProfile(
                                current.copy(
                                    name = primary.name,
                                    avatarColorHex = primary.avatarColorHex,
                                    avatarId = primary.avatarId,
                                    avatarUrl = primary.avatarUrl,
                                    profileBackgroundId = primary.profileBackgroundId,
                                    profileBackgroundUrl = primary.profileBackgroundUrl
                                )
                            )
                            Log.d(TAG, "Restored master profile display identity only")
                        }
                    }
                    Result.success(profileManager.profiles.value)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not refresh master profile identity; keeping local profiles", e)
                    Result.failure(e)
                }
            }
        }

    private suspend fun cloudPullFromRemote(force: Boolean = false): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        pullMutex.withLock {
            val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId
            val now = SystemClock.elapsedRealtime()
            if (!force && userId != null && pullFreshness.isRecent(userId, now)) {
                return@withLock Result.success(lastPulledProfiles)
            }
            try {
                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_profiles")
                }
                val remote = response.decodeList<SupabaseProfile>()

                Log.d(TAG, "pullFromRemote: fetched ${remote.size} profiles from Supabase")

                val profiles = remote.map { entry ->
                    UserProfile(
                        id = entry.profileIndex,
                        name = entry.name,
                        avatarColorHex = entry.avatarColorHex,
                        usesPrimaryAddons = entry.usesPrimaryAddons,
                        usesPrimaryPlugins = entry.usesPrimaryPlugins,
                        avatarId = entry.avatarId,
                        avatarUrl = entry.avatarUrl,
                        profileBackgroundId = entry.profileBackgroundId,
                        profileBackgroundUrl = entry.profileBackgroundUrl
                    )
                }

                val seedKey = userId?.let { "seeded_$it" }
                val needsSeed = seedKey != null && !seedPrefs.getBoolean(seedKey, false)
                val resolved = if (needsSeed) {
                    // First cloud sync for this login on this device. Profiles
                    // made here while they were device-local were never
                    // uploaded, so keep them instead of letting the cloud
                    // list (often just Profile 1) wipe them out.
                    val remoteIds = profiles.map { it.id }.toSet()
                    val localOnly = profileManager.profiles.value.filter { it.id !in remoteIds }
                    (profiles + localOnly).sortedBy { it.id }.take(ProfileManager.MAX_PROFILES)
                } else {
                    profiles
                }

                if (resolved.isNotEmpty()) {
                    profileDataStore.replaceAllProfiles(resolved)
                    Log.d(TAG, "Merged ${resolved.size} remote profiles into local store")
                }
                if (needsSeed) {
                    if (resolved.size > profiles.size) {
                        cloudPushToRemote(resolved).getOrThrow()
                        Log.d(TAG, "Uploaded ${resolved.size - profiles.size} device profiles to the cloud")
                    }
                    seedPrefs.edit().putBoolean(seedKey!!, true).apply()
                }

                val currentUserId = (authManager.authState.value as? AuthState.FullAccount)?.userId
                if (userId != null && currentUserId == userId) {
                    lastPulledProfiles = resolved
                    pullFreshness = ProfilePullFreshness(
                        userId = userId,
                        pulledAtMs = SystemClock.elapsedRealtime()
                    )
                }
                Result.success(resolved)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profiles from remote", e)
                Result.failure(e)
            }
        }
    }

    // Deleting a customer household profile must not delete another device's
    // data. Cloud accounts delete the profile's cloud data like normal Nuvio.
    suspend fun deleteProfileData(profileId: Int): Result<Unit> =
        if (cloudProfilesEnabled() && profileId != 1) cloudDeleteProfileData(profileId) else Result.success(Unit)

    private suspend fun cloudDeleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_profile_data", params)
            }

            Log.d(TAG, "Deleted remote data for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e)
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profile_locks")
            }
            val remote = response.decodeList<SupabaseProfileLockState>()
            val result = remote.associate { it.profileIndex to it.pinEnabled }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e)
            Result.failure(e)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("set_profile_pin", params)
            }
            SetProfilePinResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e)
            if (isCurrentPinRequiredError(e)) {
                SetProfilePinResult.CurrentPinRequired
            } else {
                SetProfilePinResult.Failure(e)
            }
        }
    }

    private fun isCurrentPinRequiredError(e: Throwable): Boolean =
        e.message?.contains("Current PIN is required", ignoreCase = true) == true

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("clear_profile_pin", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("verify_profile_pin", params)
            }
            val decoded = response.decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
                ?: SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = 0)
            Result.success(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e)
            Result.failure(e)
        }
    }
}
