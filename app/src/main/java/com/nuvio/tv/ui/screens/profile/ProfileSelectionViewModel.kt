package com.nuvio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.KableKillersAccountPolicy
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreateProfileResult {
    data class Created(
        val profile: UserProfile,
        val settingsCopyResult: Result<Unit>?
    ) : CreateProfileResult

    data object Failed : CreateProfileResult
}

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val avatarRepository: AvatarRepository,
    private val profileBackgroundRepository: ProfileBackgroundRepository,
    memberAccessRepository: MemberAccessRepository,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val themeDataStore: com.nuvio.tv.data.local.ThemeDataStore,
    private val authManager: AuthManager,
    private val householdPinStore: com.nuvio.tv.data.local.HouseholdPinStore,
    private val kableKillersBackup: com.nuvio.tv.core.backup.KableKillersBackupService
) : ViewModel() {
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.canCreateProfile

    private val _avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = _avatarCatalog.asStateFlow()

    val hasProfileAvatarAccess: StateFlow<Boolean> = memberAccessRepository.access
        .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasProfileBackgroundAccess: StateFlow<Boolean> = memberAccessRepository.access
        .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_BACKGROUNDS) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val profileBackgroundCatalog: StateFlow<List<ProfileBackgroundCatalogItem>> = combine(
        profileBackgroundRepository.catalog,
        hasProfileBackgroundAccess
    ) { catalog, hasAccess -> if (hasAccess) catalog else emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isCopyingSettings = MutableStateFlow(false)
    val isCopyingSettings: StateFlow<Boolean> = _isCopyingSettings.asStateFlow()

    // The locks the screen enforces. On the shared customer login this keeps
    // Profile 1 locked until the cloud confirms its PIN state, and ignores
    // PINs on household profiles (see KableKillersAccountPolicy).
    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = combine(
        profileLockStateDataStore.pinEnabled,
        profileLockStateDataStore.confirmed,
        householdPinStore.lockedProfiles,
        authManager.authState,
        kableKillersBackup.state
    ) { locks, confirmed, householdLocks, authState, _ ->
        KableKillersAccountPolicy.enforcedPinLocks(
            authState, locks, confirmed, householdLocks,
            backupOnlyCustomer = kableKillersBackup.isBackupOnlyCustomerDevice()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        KableKillersAccountPolicy.enforcedPinLocks(
            authManager.authState.value, emptyMap(), false,
            backupOnlyCustomer = kableKillersBackup.isBackupOnlyCustomerDevice()
        )
    )

    /** Signed in with the shared customer login (kablekillers6), through Nuvio or only through the backup. */
    val isCustomerAccount: StateFlow<Boolean> = combine(authManager.authState, kableKillersBackup.state) { auth, _ ->
        KableKillersAccountPolicy.isCustomerAccount(auth) || kableKillersBackup.isBackupOnlyCustomerDevice()
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        KableKillersAccountPolicy.isCustomerAccount(authManager.authState.value) || kableKillersBackup.isBackupOnlyCustomerDevice()
    )

    private val _isPinOperationInProgress = MutableStateFlow(false)
    val isPinOperationInProgress: StateFlow<Boolean> = _isPinOperationInProgress.asStateFlow()

    init {
        refreshProfilePinStates()
        viewModelScope.launch {
            hasProfileAvatarAccess.collectLatest { hasAccess ->
                loadAvatarCatalog(hasAccess)
            }
        }
        viewModelScope.launch {
            hasProfileBackgroundAccess.collectLatest { hasAccess ->
                if (hasAccess) {
                    profileBackgroundRepository.ensureLoaded()
                } else {
                    profileBackgroundRepository.invalidateCache()
                }
            }
        }
        viewModelScope.launch {
            profiles.flatMapLatest { profileList ->
                if (profileList.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        profileList.map { profile ->
                            themeDataStore.observeThemeForProfile(profile.id)
                                .map { theme -> profile.id to (theme ?: com.nuvio.tv.domain.model.AppTheme.CINEMA_BLACK) }
                        }
                    ) { entries -> entries.toMap() }
                }
            }.collectLatest { themes ->
                _profileThemes.value = themes
            }
        }
    }

    private val _profileThemes = MutableStateFlow<Map<Int, com.nuvio.tv.domain.model.AppTheme>>(emptyMap())
    val profileThemes: StateFlow<Map<Int, com.nuvio.tv.domain.model.AppTheme>> = _profileThemes.asStateFlow()

    fun loadAvatarCatalog() {
        viewModelScope.launch {
            loadAvatarCatalog(hasProfileAvatarAccess.value)
        }
    }

    private suspend fun loadAvatarCatalog(hasMemberAccess: Boolean) {
        try {
            _avatarCatalog.value = avatarRepository.getAvatarCatalog(hasMemberAccess)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("ProfileSelectionVM", "Failed to load avatar catalog", error)
        }
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return avatarRepository.getAvatarImageUrl(avatarId, _avatarCatalog.value)
    }

    fun loadProfileBackground(id: String) {
        if (hasProfileBackgroundAccess.value) profileBackgroundRepository.loadSelectedAndPreload(id)
    }

    fun preloadProfileBackgrounds() {
        if (hasProfileBackgroundAccess.value) profileBackgroundRepository.preloadImages()
    }

    var isSelectingProfile = false
        private set

    fun selectProfile(id: Int, onComplete: () -> Unit, onFailure: () -> Unit) {
        if (isSelectingProfile) return
        isSelectingProfile = true
        viewModelScope.launch {
            try {
                profileManager.setActiveProfile(id)
                onComplete()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to select profile", error)
                onFailure()
            } finally {
                isSelectingProfile = false
            }
        }
    }

    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        copyFromProfileId: Int? = null,
        copyProviderCredentials: Boolean = false,
        onComplete: (CreateProfileResult) -> Unit = {}
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            var createdProfileId: Int? = null
            val result = try {
                val profile = profileManager.createProfile(
                    name = name,
                    avatarColorHex = avatarColorHex,
                    avatarId = avatarId
                )
                if (profile != null) {
                    createdProfileId = profile.id
                    // On the shared customer account household profiles stay
                    // local (pushToRemote below is a no-op there). Other
                    // accounts upload the profile so every device sees it.
                    // Every new profile inherits Profile 1. Do not let
                    // callers bypass this by omitting the optional UI argument.
                    val copyResult = profileSettingsSyncService.copyProfileSetup(
                        sourceProfileId = 1,
                        targetProfileId = profile.id,
                        copyProviderCredentials = false
                    )
                    if (copyResult.isFailure) {
                        // Never present a half-configured household profile as
                        // successfully created. The next attempt starts clean.
                        Log.e("ProfileSelectionVM", "Primary profile inheritance failed", copyResult.exceptionOrNull())
                        profileManager.deleteProfile(profile.id)
                        createdProfileId = null
                        CreateProfileResult.Failed
                    } else {
                        profileSyncService.pushToRemote()
                        refreshProfilePinStates()
                        createdProfileId = null
                        CreateProfileResult.Created(profile, copyResult)
                    }
                } else {
                    CreateProfileResult.Failed
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to create profile", error)
                createdProfileId?.let { id ->
                    try {
                        profileManager.deleteProfile(id)
                    } catch (cleanupError: Exception) {
                        Log.e("ProfileSelectionVM", "Failed to clean up incomplete profile $id", cleanupError)
                    }
                }
                CreateProfileResult.Failed
            } finally {
                _isCreating.value = false
            }
            onComplete(result)
        }
    }

    fun copyProfileSettings(
        sourceProfileId: Int,
        targetProfileId: Int,
        copyProviderCredentials: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (_isCopyingSettings.value) return
        if (targetProfileId == KableKillersAccountPolicy.PRIMARY_PROFILE_ID) {
            // Profile 1 is the master every other profile mirrors; never overwrite it.
            onComplete(Result.failure(IllegalArgumentException("Profile 1 cannot be overwritten")))
            return
        }
        viewModelScope.launch {
            _isCopyingSettings.value = true
            val result = try {
                profileSettingsSyncService.copyProfileSetup(
                    sourceProfileId = sourceProfileId,
                    targetProfileId = targetProfileId,
                    copyProviderCredentials = copyProviderCredentials
                )
            } finally {
                _isCopyingSettings.value = false
            }
            onComplete(result)
        }
    }

    fun updateProfile(profile: UserProfile) {
        if (_isSaving.value) return
        viewModelScope.launch {
            _isSaving.value = true
            profileManager.updateProfile(profile)
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
            _isSaving.value = false
        }
    }

    fun deleteProfile(id: Int) {
        viewModelScope.launch {
            profileManager.deleteProfile(id)
            householdPinStore.clearPin(id)
            profileSyncService.deleteProfileData(id)
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
        }
    }

    fun refreshProfilePinStates() {
        viewModelScope.launch {
            var attempt = 0
            while (attempt < 4) {
                val result = profileSyncService.pullProfileLockStates()
                if (result.isSuccess) {
                    profileLockStateDataStore.replaceAll(result.getOrNull().orEmpty())
                    return@launch
                }
                Log.e(
                    "ProfileSelectionVM",
                    "Failed to refresh profile PIN states (attempt=$attempt)",
                    result.exceptionOrNull()
                )
                attempt++
                delay(2000L * attempt)
            }
        }
    }

    fun isProfilePinEnabled(profileId: Int): Boolean {
        return profilePinEnabled.value[profileId] == true
    }

    fun setProfilePin(
        profileId: Int,
        pin: String,
        currentPin: String? = null,
        onComplete: (SetProfilePinResult) -> Unit
    ) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            if (usesDevicePin(profileId)) {
                val result = setDevicePin(profileId, pin, currentPin)
                _isPinOperationInProgress.value = false
                onComplete(result)
                return@launch
            }
            val result = profileSyncService.setProfilePin(profileId, pin, currentPin)
            // Server reporting CurrentPinRequired means a PIN exists remotely —
            // reconcile local cache so we never forget it again.
            if (result is SetProfilePinResult.Success || result is SetProfilePinResult.CurrentPinRequired) {
                profileLockStateDataStore.setPinEnabled(profileId, true)
            }
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun clearProfilePin(profileId: Int, currentPin: String? = null, onComplete: (Boolean) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            if (usesDevicePin(profileId)) {
                val allowed = currentPin != null && verifyDevicePinOrOwner(profileId, currentPin).unlocked
                if (allowed) householdPinStore.clearPin(profileId)
                _isPinOperationInProgress.value = false
                onComplete(allowed)
                return@launch
            }
            val success = profileSyncService.clearProfilePin(profileId, currentPin).isSuccess
            if (success) {
                profileLockStateDataStore.setPinEnabled(profileId, false)
            }
            _isPinOperationInProgress.value = false
            onComplete(success)
        }
    }

    fun verifyProfilePin(profileId: Int, pin: String, onComplete: (Result<SupabaseProfilePinVerifyResult>) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = if (usesDevicePin(profileId)) {
                Result.success(verifyDevicePinOrOwner(profileId, pin))
            } else {
                verifyCloudPinOrRestorePin(profileId, pin)
            }
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    // ---- Household (customer) profile PINs: saved on this device only ----

    private fun usesDevicePin(profileId: Int): Boolean =
        KableKillersAccountPolicy.usesDevicePin(
            authManager.authState.value, profileId,
            backupOnlyCustomer = kableKillersBackup.isBackupOnlyCustomerDevice()
        )

    /**
     * Profile 1's PIN is checked by Nuvio. When Nuvio can't be reached (or this device signed in only
     * through the backup), the owner's restore PIN opens Profile 1 instead, through the Kable Killers
     * backup, and lets this device send Profile 1 changes to the backup while Nuvio is down.
     */
    private suspend fun verifyCloudPinOrRestorePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> {
        val canUseBackup = profileId == KableKillersAccountPolicy.PRIMARY_PROFILE_ID &&
            kableKillersBackup.enabled && kableKillersBackup.state.value.hasDeviceKey
        if (canUseBackup && authManager.authState.value !is com.nuvio.tv.domain.model.AuthState.FullAccount) {
            return Result.success(verifyRestorePin(pin))
        }
        val cloud = profileSyncService.verifyProfilePin(profileId, pin)
        if (cloud.isFailure && canUseBackup) return Result.success(verifyRestorePin(pin))
        return cloud
    }

    private suspend fun verifyRestorePin(pin: String): SupabaseProfilePinVerifyResult =
        kableKillersBackup.unlockWithRestorePin(pin).fold(
            onSuccess = { SupabaseProfilePinVerifyResult(unlocked = true) },
            onFailure = { error ->
                val tooMany = (error as? com.nuvio.tv.core.backup.KableKillersBackupHttpException)?.status == 429
                SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = if (tooMany) 900 else 0)
            }
        )

    private suspend fun setDevicePin(profileId: Int, pin: String, currentPin: String?): SetProfilePinResult {
        return try {
            if (householdPinStore.hasPin(profileId)) {
                if (currentPin == null) return SetProfilePinResult.CurrentPinRequired
                if (!verifyDevicePinOrOwner(profileId, currentPin).unlocked) {
                    return SetProfilePinResult.Failure(IllegalArgumentException("Current PIN is incorrect"))
                }
            }
            householdPinStore.setPin(profileId, pin)
            SetProfilePinResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("ProfileSelectionVM", "Failed to save device PIN", error)
            SetProfilePinResult.Failure(error)
        }
    }

    /**
     * Checks a household profile's own PIN. If that fails, the owner's Profile 1
     * PIN also unlocks it, so a parent who forgets their PIN can get help.
     * The override is only tried when Profile 1 really has a PIN.
     */
    private suspend fun verifyDevicePinOrOwner(profileId: Int, pin: String): SupabaseProfilePinVerifyResult {
        val local = householdPinStore.verify(profileId, pin)
        if (local.unlocked) return SupabaseProfilePinVerifyResult(unlocked = true)
        val primaryHasPin = profileLockStateDataStore.pinEnabled.first()[KableKillersAccountPolicy.PRIMARY_PROFILE_ID] == true
        if (primaryHasPin || kableKillersBackup.isBackupOnlyCustomerDevice()) {
            val owner = verifyCloudPinOrRestorePin(KableKillersAccountPolicy.PRIMARY_PROFILE_ID, pin).getOrNull()
            if (owner?.unlocked == true) return SupabaseProfilePinVerifyResult(unlocked = true)
        }
        return SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = local.retryAfterSeconds)
    }
}
