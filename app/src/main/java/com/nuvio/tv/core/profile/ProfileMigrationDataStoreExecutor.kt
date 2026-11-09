package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.core.sync.StartupSyncService
import java.io.File

/**
 * Explicit developer-only DataStore copy orchestration.
 *
 * Not invoked by login, startup, or profile selection. The caller must first
 * stop independent background/realtime sync and all other profile writers. The factory
 * barrier only covers stores managed by that factory. This never activates
 * customer-local mode or marks the account migration COMPLETED.
 */
internal object ProfileMigrationDataStoreExecutor {
    suspend fun copyGuardedDataStores(
        factory: ProfileDataStoreFactory,
        startupSyncService: StartupSyncService,
        directory: File,
        verifiedAccountId: String,
        sourceId: Int,
        targetId: Int,
        currentVerifiedAccountId: () -> String?
    ): ProfileMigrationBatchCopy.Result? {
        require(sourceId in 2..6 && targetId in 2..6 && sourceId != targetId)
        require(verifiedAccountId.isNotBlank() &&
            verifiedAccountId == verifiedAccountId.trim() &&
            verifiedAccountId.none { it.isISOControl() })
        check(currentVerifiedAccountId() == verifiedAccountId) {
            "Authenticated account changed before migration"
        }
        return startupSyncService.withStartupSyncPausedForMigration {
            check(currentVerifiedAccountId() == verifiedAccountId) {
                "Authenticated account changed while pausing startup sync"
            }
            ProfileMigrationExclusiveLock.tryWithSuspendingLock(directory) {
                factory.withGuardedProfileRemap(
                verifiedAccountId, sourceId, targetId, currentVerifiedAccountId
            ) { preview ->
                val items = ProfileMigrationStoreManifest.fromFactoryPreview(
                    directory, preview, sourceId, targetId
                )
                check(currentVerifiedAccountId() == verifiedAccountId) {
                    "Authenticated account changed before migration copy"
                }
                ProfileMigrationTransaction.copyBatchWithJournal(
                    directory, verifiedAccountId, sourceId, targetId, items
                )
                }
            }
        }
    }
}
