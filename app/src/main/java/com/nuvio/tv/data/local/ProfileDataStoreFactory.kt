package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.core.profile.ProfileShadowBackupMigrationPlan
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private val remapAccessGate = ProfileRemapStoreAccessGate()
    private val retainedStandaloneDataStoreNames = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        "debug_settings",
        "device_local_player_prefs",
        "profile_lock_state",
        "profile_settings",
        "sentry_settings",
        "torrent_settings",
        "tv_channel_prefs"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            remapAccessGate.checkAccess(profileId)
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    /**
     * Future migration orchestrators can hold this barrier while copying and
     * reconciling source/target profile stores. Begin and get() share the
     * factory monitor so no source or target store can open between the cache
     * check and installation of the barrier.
     *
     * A barrier alone does NOT quiesce already handed-out DataStores, other
     * processes, or background writers. Callers must stop those first, hold
     * ProfileMigrationExclusiveLock as well, and close the token in finally.
     */
    internal fun beginProfileRemapAccessBlock(sourceId: Int, targetId: Int): AutoCloseable {
        synchronized(lock) {
            val cachedIds = cache.keys.mapNotNull { key ->
                Regex("_p([0-9]+)$").find(key)?.groupValues?.get(1)?.toIntOrNull()
            }.toSet()
            remapAccessGate.begin(sourceId, targetId, cachedIds)
        }
        var released = false
        return AutoCloseable {
            synchronized(lock) {
                // A stale or double-closed token must not release a later
                // migration barrier for the same source/target IDs.
                if (!released) {
                    remapAccessGate.end(sourceId, targetId)
                    released = true
                }
            }
        }
    }

    /**
     * A read-only, guarded migration preflight. The returned preparation keeps
     * new source/target DataStores blocked until close() is called; the preview
     * itself is built AFTER the access gate has been installed.
     *
     * This is not an authorization to run migration: it cannot stop references
     * handed out outside this factory, background sync, or another process.
     * A future orchestrator must independently stop writers, authenticate the
     * account, and use ProfileMigrationExclusiveLock for each stage.
     */
    internal class ProfileRemapPreparation(
        val manifest: Map<String, String>,
        private val accessBlock: AutoCloseable
    ) : AutoCloseable {
        override fun close() = accessBlock.close()
    }

    internal fun prepareProfileRemap(sourceId: Int, targetId: Int): ProfileRemapPreparation {
        val block = beginProfileRemapAccessBlock(sourceId, targetId)
        try {
            return ProfileRemapPreparation(
                manifest = previewProfileFileMoves(sourceId, targetId),
                accessBlock = block
            )
        } catch (failure: Throwable) {
            block.close()
            throw failure
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        synchronized(lock) { remapAccessGate.beginMutation(profileId) }
        try {
            deletedProfileIds.add(profileId)
            val suffix = "_p$profileId"
            val keysToRemove = synchronized(lock) {
                cache.keys.filter { it.endsWith(suffix) }
            }
            for (key in keysToRemove) {
                val scoped = synchronized(lock) { cache.remove(key) } ?: continue
                runCatching { scoped.store.edit { it.clear() } }
                // Cancel the scope so DataStore releases the file from its
                // active-files registry before it can be opened again.
                scoped.job.cancel()
                scoped.job.join()
            }
        } finally {
            synchronized(lock) { remapAccessGate.endMutation() }
        }
    }

    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO) {
        synchronized(lock) { remapAccessGate.beginMutation() }
        try {
            val cachedStores = synchronized(lock) { cache.toMap() }
            cachedStores.values.forEach { scoped ->
                runCatching { scoped.store.edit { it.clear() } }
            }
            deletedProfileIds.clear()
            corruptedFileNames.clear()

            val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it.preferences_pb" }
            val dataStoreDir = File(context.filesDir, "datastore")
            if (!dataStoreDir.exists()) return@withContext
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name !in cachedFileNames && isProfileScopedDataStoreFile(file.name)) {
                    file.delete()
                }
            }
        } finally {
            synchronized(lock) { remapAccessGate.endMutation() }
        }
    }

    /**
     * Read-only inventory for a future profile-ID migration.
     *
     * Includes stores that are not currently cached/open. A migration must
     * account for every returned file before changing any profile identifier.
     * Never rename DataStore files while their instances are active.
     */
    fun inventoryProfileScopedFiles(profileId: Int): List<String> {
        require(profileId > 1) { "Only secondary profiles can be remapped" }
        val suffix = "_p$profileId.preferences_pb"
        val directory = File(context.filesDir, "datastore")
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(suffix) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

    /**
     * Non-mutating remap audit. Refuse any target with existing data, including
     * stores that have not yet been opened during this app session.
     */
    fun auditProfileRemapSourceAndTarget(sourceId: Int, targetId: Int): ProfileRemapInventory {
        require(sourceId > 1 && targetId > 1 && sourceId != targetId) {
            "Remapping requires distinct secondary profile IDs"
        }
        val source = inventoryProfileScopedFiles(sourceId)
        val target = inventoryProfileScopedFiles(targetId)
        val sourceSuffix = "_p$sourceId"
        val targetSuffix = "_p$targetId"
        val cachedSourceStores = synchronized(lock) {
            cache.keys.filter { it.endsWith(sourceSuffix) }.sorted()
        }
        val cachedTargetStores = synchronized(lock) {
            cache.keys.filter { it.endsWith(targetSuffix) }.sorted()
        }
        return ProfileRemapInventory(
            sourceId = sourceId,
            targetId = targetId,
            sourceFiles = source,
            cachedSourceStores = cachedSourceStores,
            existingTargetFiles = target,
            cachedTargetStores = cachedTargetStores,
            mayProceedToSeparateMigrationStep = source.isNotEmpty() &&
                cachedSourceStores.isEmpty() && target.isEmpty() && cachedTargetStores.isEmpty()
        )
    }

    data class ProfileRemapInventory(
        val sourceId: Int,
        val targetId: Int,
        val sourceFiles: List<String>,
        val cachedSourceStores: List<String>,
        val existingTargetFiles: List<String>,
        val cachedTargetStores: List<String>,
        val mayProceedToSeparateMigrationStep: Boolean
    )

    /**
     * Construct a read-only file move manifest. Every destination must be empty
     * and both profile stores must be closed before any future executor runs.
     */
    fun previewProfileFileMoves(sourceId: Int, targetId: Int): Map<String, String> {
        val audit = auditProfileRemapSourceAndTarget(sourceId, targetId)
        check(audit.mayProceedToSeparateMigrationStep) {
            "Profile remapping is unsafe: source/target stores are active, missing, or occupied"
        }
        val sourceSuffix = "_p$sourceId.preferences_pb"
        val manifest = audit.sourceFiles.associateWith { sourceFile ->
            check(sourceFile.endsWith(sourceSuffix))
            sourceFile.removeSuffix(sourceSuffix) + "_p$targetId.preferences_pb"
        }
        check(manifest.values.size == manifest.values.toSet().size) {
            "Multiple source stores map to the same destination"
        }
        val directory = File(context.filesDir, "datastore")
        val cachedDuringPreview = synchronized(lock) {
            cache.keys.any { it.endsWith("_p$sourceId") || it.endsWith("_p$targetId") }
        }
        check(!cachedDuringPreview) {
            "Profile DataStore opened during migration preview; refusing remap"
        }
        check(manifest.keys.all { sourceName ->
            val source = File(directory, sourceName)
            source.isFile && source.length() > 0L
        }) {
            "Source store is missing or empty; refusing remap"
        }
        check(manifest.values.none { File(directory, it).exists() }) {
            "Destination store appeared after inventory; refusing remap"
        }
        // The copy coordinator expects a complete inventory including
        // shadow recovery backups. A preview containing only primary files
        // would be rejected whenever a profile has recovery data.
        val backupPlan = ProfileShadowBackupMigrationPlan.inspect(
            directory, sourceId, targetId, manifest
        )
        return manifest + backupPlan.backupsToCopy
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        synchronized(lock) {
            remapAccessGate.checkAccess(profileId)
            deletedProfileIds.remove(profileId)
        }
    }

    private fun isProfileScopedDataStoreFile(fileName: String): Boolean {
        if (!fileName.endsWith(".preferences_pb")) return false
        val dataStoreName = fileName.removeSuffix(".preferences_pb")
        return dataStoreName !in retainedStandaloneDataStoreNames
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        // Ensure the datastore directory exists — prevents ENOENT on first read
        // when a profile-scoped file hasn't been created yet.
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) {
            dataStoreDir.mkdirs()
        }
        // DataStore 1.1.x (okio-based) can throw FileNotFoundException if the file
        // doesn't exist yet and a race condition occurs. Pre-create an empty file
        // to avoid this edge case (DataStore will overwrite on first write).
        val targetFile = File(dataStoreDir, "$fileName.preferences_pb")
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — attempting shadow copy recovery")
                val recovered = recoverFromShadowCopy(fileName)
                if (recovered != null) {
                    Log.i("ProfileDataStoreFactory", "DataStore recovered from shadow copy ($fileName)")
                    recovered
                } else {
                    Log.e("ProfileDataStoreFactory", "DataStore shadow copy unavailable ($fileName) — resetting to empty preferences")
                    corruptedFileNames.add(fileName)
                    emptyPreferences()
                }
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )

        // Wrap store to persist a shadow copy after each successful read.
        // The shadow copy is written once on first data emission (app start),
        // ensuring a consistent backup exists before any corruption can occur.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val scoped = ScopedDataStore(wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    internal fun writeShadowCopy(fileName: String, preferences: Preferences) {
        val sourceFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb")
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        // Shadow backups are written asynchronously after DataStore changes.
        // Count them as in-flight mutations so a remap cannot begin midway
        // through a copy, and reject late writes to blocked source/target IDs.
        val profileId = Regex("_p([0-9]+)$").find(fileName)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        try {
            synchronized(lock) { remapAccessGate.beginMutation(profileId) }
            try {
                if (sourceFile.exists() && sourceFile.length() > 0) {
                    sourceFile.copyTo(backupFile, overwrite = true)
                }
            } finally {
                synchronized(lock) { remapAccessGate.endMutation() }
            }
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun recoverFromShadowCopy(fileName: String): Preferences? {
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        if (!backupFile.exists() || backupFile.length() == 0L) return null
        return try {
            val source = backupFile.inputStream().use { input ->
                okio.Buffer().apply { readFrom(input) }
            }
            val preferences = kotlinx.coroutines.runBlocking {
                androidx.datastore.preferences.core.PreferencesSerializer.readFrom(source)
            }
            Log.i("ProfileDataStoreFactory", "Parsed shadow copy for $fileName (${preferences.asMap().size} keys)")
            preferences
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Shadow copy recovery failed for $fileName: ${e.message}")
            null
        }
    }
}


/**
 * Thin wrapper around a DataStore that writes a shadow copy of the underlying
 * preferences file after the first successful read and after each edit.
 * This ensures a known-good backup exists for corruption recovery.
 */
private class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory
) : DataStore<Preferences> {

    @Volatile
    private var shadowWritten = false

    override val data: kotlinx.coroutines.flow.Flow<Preferences>
        get() = delegate.data.also {
            if (!shadowWritten) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val prefs = delegate.data.first()
                        if (prefs != emptyPreferences()) {
                            factory.writeShadowCopy(fileName, prefs)
                            shadowWritten = true
                        }
                    } catch (_: Exception) { }
                }
            }
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        scope.launch(Dispatchers.IO) {
            try {
                factory.writeShadowCopy(fileName, result)
                shadowWritten = true
            } catch (_: Exception) { }
        }
        return result
    }
}
