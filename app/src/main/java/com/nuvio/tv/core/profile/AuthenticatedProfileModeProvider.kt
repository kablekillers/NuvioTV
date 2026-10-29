package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a mode assignment only for the currently authenticated account.
 *
 * Deliberately has no public write API: customer migration must atomically
 * persist its verified assignment after completing all preflight checks.
 * Until rollout is enabled, every account retains legacy cloud behavior.
 */
@Singleton
class AuthenticatedProfileModeProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val authManager: AuthManager
) {
    private val preferences = context.getSharedPreferences(
        "verified_profile_account_modes_v1",
        Context.MODE_PRIVATE
    )

    internal fun currentMode(): AccountProfileMode {
        if (!DeviceLocalProfilesFeature.ENABLED) return AccountProfileMode.LEGACY_CLOUD
        val accountId = authManager.verifiedProfileAccountId
            ?: return AccountProfileMode.LEGACY_CLOUD
        if (accountId.isBlank() || accountId != accountId.trim()) {
            return AccountProfileMode.LEGACY_CLOUD
        }
        // A per-account namespace avoids one login overwriting another login's
        // verified assignment. Legacy single-slot records remain readable only
        // when their stored account ID exactly matches the authenticated ID.
        val keys = AccountModeStorageKeys.forVerifiedAccount(accountId)
        val modeKey = keys.mode
        val verifiedIdKey = keys.verifiedId
        val migrationKey = keys.migrationComplete
        // Any partial scoped record must fail closed, not silently inherit
        // a legacy single-slot assignment.
        val hasScopedRecord = preferences.contains(verifiedIdKey) ||
            preferences.contains(modeKey) || preferences.contains(migrationKey)
        if (hasScopedRecord && (!preferences.contains(verifiedIdKey) ||
                !preferences.contains(modeKey) || !preferences.contains(migrationKey))) {
            return AccountProfileMode.LEGACY_CLOUD
        }
        val storedAccountId = if (hasScopedRecord) {
            preferences.getString(verifiedIdKey, null)
        } else {
            preferences.getString("assigned_account_id", null)
        }
        val storedMode = if (hasScopedRecord) {
            preferences.getString(modeKey, null)
        } else {
            preferences.getString("assigned_mode", null)
        }
        val migrationComplete = if (hasScopedRecord) {
            preferences.getBoolean(migrationKey, false)
        } else {
            preferences.getBoolean("migration_complete", false)
        }
        return AccountProfileModeResolver.resolve(
            authenticatedAccountId = accountId,
            storedAccountId = storedAccountId,
            storedMode = storedMode,
            migrationEnabled = migrationComplete
        )
    }
}
