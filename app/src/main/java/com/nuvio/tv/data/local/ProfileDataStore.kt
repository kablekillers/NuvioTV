package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.DeviceProfileMetadataRemapPlanner
import com.nuvio.tv.core.profile.ProfileMigrationJournal
import com.nuvio.tv.core.profile.ProfileMigrationMetadataReconciliation
import com.nuvio.tv.core.profile.ProfileMigrationRecoveryVerifier
import java.io.File
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        android.util.Log.e("ProfileDataStore", "DataStore corrupted: ${ex.message} — resetting to empty")
        emptyPreferences()
    }
)

@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    private val dataStore = context.profileDataStore

    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")
    private val hasEverSelectedProfileKey = booleanPreferencesKey("profile_has_ever_selected")
    private val rememberLastProfileEnabledKey = booleanPreferencesKey("remember_last_profile_enabled")
    private val confirmExitEnabledKey = booleanPreferencesKey("confirm_exit_enabled")
    private val startupSplashEnabledKey = booleanPreferencesKey("startup_splash_enabled")

    private val profileListType = Types.newParameterizedType(List::class.java, ProfileJson::class.java)

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        val json = prefs[profilesJsonKey]
        if (json != null) {
            parseProfiles(json)
        } else {
            listOf(defaultPrimaryProfile())
        }
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    val hasEverSelectedProfile: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasEverSelectedProfileKey] ?: false
    }

    val rememberLastProfileEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rememberLastProfileEnabledKey] ?: false
    }

    val confirmExitEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[confirmExitEnabledKey] ?: false
    }

    val startupSplashEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[startupSplashEnabledKey] ?: true
    }

    suspend fun setActiveProfile(id: Int) {
        dataStore.edit { prefs ->
            prefs[activeProfileIdKey] = id
            prefs[hasEverSelectedProfileKey] = true
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rememberLastProfileEnabledKey] = enabled
        }
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[confirmExitEnabledKey] = enabled
        }
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[startupSplashEnabledKey] = enabled
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            if ((prefs[activeProfileIdKey] ?: 1) == id) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        dataStore.edit { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            val activeId = prefs[activeProfileIdKey] ?: 1
            if (normalizedProfiles.none { it.id == activeId }) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    /**
     * Device-local profile mode: refresh only the shared Main profile.
     * Never replace local secondary profiles or reset the selected local profile.
     * The caller must filter remote profiles to Main before invoking this method.
     */
    suspend fun mergeRemoteMainProfile(mainProfile: UserProfile) {
        require(mainProfile.id == 1) { "Only the Main profile may be merged from cloud" }
        dataStore.edit { prefs ->
            val local = parseProfiles(prefs[profilesJsonKey])
            val merged = local.map { if (it.id == 1) mainProfile else it }
            prefs[profilesJsonKey] = serializeProfiles(normalizeProfiles(merged))
        }
    }

    /**
     * Migration-only metadata commit, deliberately not called from startup.
     *
     * The copy-only coordinator must first verify all profile-scoped files and
     * leave a VERIFIED journal for this exact account and ID remap. This edit
     * atomically updates profile metadata and active selection. If anything has
     * changed since the caller captured the snapshot, leave data untouched.
     *
     * The migration journal intentionally remains VERIFIED afterwards: an
     * independent recovery/reconciliation stage must validate persistence and
     * explicitly mark COMPLETED before enabling customer device-local mode.
     */
    suspend fun commitVerifiedProfileMetadataRemap(
        verifiedAccountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        expectedProfiles: List<UserProfile>,
        expectedActiveProfileId: Int,
        verifiedFileManifest: Map<String, String>,
        currentVerifiedAccountId: () -> String?
    ) {
        require(verifiedAccountId.isNotBlank() &&
            verifiedAccountId == verifiedAccountId.trim() &&
            currentVerifiedAccountId() == verifiedAccountId) {
            "The same verified account must be signed in before migration"
        }
        val directory = File(context.filesDir, "datastore")
        dataStore.edit { prefs ->
            // DataStore edits may be queued. Never trust account identity
            // captured before this suspend point if logout/login occurred.
            check(currentVerifiedAccountId() == verifiedAccountId) {
                "The signed-in account changed during profile migration"
            }
            val journal = ProfileMigrationJournal.read(directory)
                ?: error("No verified profile migration journal")
            check(journal.accountId == verifiedAccountId &&
                journal.sourceId == sourceProfileId &&
                journal.targetId == targetProfileId &&
                journal.phase == ProfileMigrationJournal.Phase.VERIFIED) {
                "Profile migration journal is not verified for this account and remap"
            }
            check(!File(directory, "profile_migration_journal.tmp").exists()) {
                "Unresolved migration journal temporary file"
            }

            check(ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                directory = directory,
                verifiedAccountId = verifiedAccountId,
                sourceProfileId = sourceProfileId,
                targetProfileId = targetProfileId,
                manifest = verifiedFileManifest
            )) {
                "Profile-scoped file inventory or checksum verification failed"
            }

            val currentProfiles = parseProfiles(prefs[profilesJsonKey])
            val currentActiveId = prefs[activeProfileIdKey] ?: 1
            check(currentProfiles == expectedProfiles &&
                currentActiveId == expectedActiveProfileId) {
                "Profile metadata changed during migration; reconciliation required"
            }
            val proposed = DeviceProfileMetadataRemapPlanner.propose(
                existingProfiles = currentProfiles,
                activeProfileId = currentActiveId,
                localIdRemap = mapOf(sourceProfileId to targetProfileId)
            )
            // Recheck after potentially slow checksum and metadata validation,
            // before mutating either key in the atomic DataStore edit.
            check(currentVerifiedAccountId() == verifiedAccountId) {
                "The signed-in account changed before metadata commit"
            }
            prefs[profilesJsonKey] = serializeProfiles(proposed.profiles)
            prefs[activeProfileIdKey] = proposed.activeProfileId
        }
    }

    /**
     * Read-only checkpoint after the metadata commit. This does not complete
     * migration or authorize device-local mode. A future recovery coordinator
     * must independently reconcile journal ownership and every data source.
     */
    suspend fun verifyPersistedProfileMetadataRemap(
        verifiedAccountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        originalProfiles: List<UserProfile>,
        originalActiveProfileId: Int,
        verifiedFileManifest: Map<String, String>
    ): Boolean {
        return runCatching {
            val directory = File(context.filesDir, "datastore")
            if (!ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                    directory,
                    verifiedAccountId,
                    sourceProfileId,
                    targetProfileId,
                    verifiedFileManifest
                )
            ) return@runCatching false

            val persisted = dataStore.data.first()
            val persistedJson = persisted[profilesJsonKey] ?: return@runCatching false
            ProfileMigrationMetadataReconciliation.matches(
                originalProfiles = originalProfiles,
                originalActiveId = originalActiveProfileId,
                sourceId = sourceProfileId,
                targetId = targetProfileId,
                persistedProfiles = parseProfiles(persistedJson),
                persistedActiveId = persisted[activeProfileIdKey] ?: 1
            )
        }.getOrDefault(false)
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = context.getString(R.string.profile_default_name, 1),
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
            val parsed = adapter.fromJson(json) ?: return listOf(defaultPrimaryProfile())
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
        return adapter.toJson(profiles.map { ProfileJson.fromDomain(it) })
    }
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
internal data class ProfileJson(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        usesPrimaryPlugins = usesPrimaryPlugins,
        avatarId = avatarId,
        avatarUrl = avatarUrl,
        profileBackgroundId = profileBackgroundId,
        profileBackgroundUrl = profileBackgroundUrl
    )

    companion object {
        fun fromDomain(profile: UserProfile) = ProfileJson(
            id = profile.id,
            name = profile.name,
            avatarColorHex = profile.avatarColorHex,
            usesPrimaryAddons = profile.usesPrimaryAddons,
            usesPrimaryPlugins = profile.usesPrimaryPlugins,
            avatarId = profile.avatarId,
            avatarUrl = profile.avatarUrl,
            profileBackgroundId = profile.profileBackgroundId,
            profileBackgroundUrl = profile.profileBackgroundUrl
        )
    }
}
