package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.model.UserProfile
import java.io.File

/**
 * Explicit, recovery-safe final checkpoint for ONE profile-ID remap.
 *
 * Not called on app launch. The future migration orchestrator must hold an
 * exclusive migration lock, stop all profile writers and independently
 * authenticate account ownership before invoking this method. No source
 * files are removed and no cloud profile records are deleted.
 */
internal object ProfileMigrationCompletionCoordinator {
    suspend fun completeVerifiedRemap(
        directory: File,
        verifiedAccountId: String,
        sourceProfileId: Int,
        targetProfileId: Int,
        originalProfiles: List<UserProfile>,
        originalActiveProfileId: Int,
        verifiedFileManifest: Map<String, String>,
        profileDataStore: ProfileDataStore,
        currentVerifiedAccountId: () -> String?
    ): Boolean {
        if (verifiedAccountId.isBlank() || verifiedAccountId != verifiedAccountId.trim() ||
            currentVerifiedAccountId() != verifiedAccountId
        ) return false
        // Both checks must succeed again immediately before writing COMPLETED.
        // A VERIFIED record only means file copies were checked; it cannot by
        // itself prove that profile metadata was persisted correctly.
        return try {
            val journal = ProfileMigrationJournal.read(directory) ?: return false
            if (journal.accountId != verifiedAccountId ||
                journal.sourceId != sourceProfileId ||
                journal.targetId != targetProfileId ||
                journal.phase != ProfileMigrationJournal.Phase.VERIFIED
            ) return false

            if (!ProfileMigrationRecoveryVerifier.verifyCopiedFiles(
                    directory, verifiedAccountId, sourceProfileId,
                    targetProfileId, verifiedFileManifest
                )
            ) return false

            if (!profileDataStore.verifyPersistedProfileMetadataRemap(
                    verifiedAccountId, sourceProfileId, targetProfileId,
                    originalProfiles, originalActiveProfileId, verifiedFileManifest
                )
            ) return false

            // Metadata verification suspends. A logout or login switch during
            // that read must never complete the previous account's migration.
            if (currentVerifiedAccountId() != verifiedAccountId) return false

            // Journal ownership and phase are revalidated by write(). If the
            // write fails, leave VERIFIED in place for explicit reconciliation.
            ProfileMigrationJournal.write(
                directory, journal.copy(phase = ProfileMigrationJournal.Phase.COMPLETED)
            )
            ProfileMigrationJournal.hasCompletedMigrationForAccount(directory, verifiedAccountId)
        } catch (_: Exception) {
            false
        }
    }
}
