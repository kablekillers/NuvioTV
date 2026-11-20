package com.nuvio.tv.core.backup

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.KableKillersAccountPolicy
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.SyncHomeCatalogPayload
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.RemotePluginInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KableKillersBackup"
private const val SYNC_MIN_INTERVAL_MS = 10 * 60_000L
private const val AUTO_RESTORE_MIN_INTERVAL_MS = 60 * 60_000L

/**
 * Keeps this login's Profile 1 setup copied to the Kable Killers backup on
 * Cloudflare, and restores it from there when Nuvio can't.
 *
 * - After every successful Nuvio sync, the backup re-reads Profile 1 from Nuvio
 *   (using this device's sign-in) and saves a copy. Nothing is written to Nuvio.
 * - Restoring writes only Profile 1's add-ons, plugins, collections, Home rows,
 *   settings and profile list, through the same code a Nuvio pull uses. Library,
 *   Continue Watching and watch progress are never touched.
 * - The customer-login rule is kept: on kablekillers6 only Profile 1's name and
 *   look are restored, household profiles stay local.
 */
@Singleton
class KableKillersBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: Auth,
    private val authManager: AuthManager,
    private val store: KableKillersBackupStore,
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val addonSyncService: AddonSyncService,
    private val addonPreferences: AddonPreferences,
    private val addonRepository: AddonRepositoryImpl,
    private val pluginManager: PluginManager,
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client: KableKillersBackupClient? =
        KableKillersBackupConfig.BASE_URL.takeIf { it.isNotBlank() }?.let { KableKillersBackupClient(it) }
    private val restoreMutex = Mutex()

    @Volatile private var lastSyncAttemptMs = 0L
    @Volatile private var lastAutoRestoreMs = 0L

    val enabled: Boolean get() = client != null
    val state = store.state

    // ---------- Hooks called by StartupSyncService ----------

    fun onAppStart() {
        if (!enabled) return
        scope.launch { runCatching { ping() }.onFailure { Log.w(TAG, "Backup check-in failed: ${it.message}") } }
    }

    fun onNuvioSyncSucceeded() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAttemptMs < SYNC_MIN_INTERVAL_MS) return
        lastSyncAttemptMs = now
        scope.launch { backupNow(force = false).onFailure { Log.w(TAG, "Backup after Nuvio sync failed: ${it.message}") } }
    }

    /** Nuvio couldn't be reached at all. If Profile 1 is empty on this device, fill it from the backup. */
    fun onNuvioSyncFailed() {
        if (!enabled) return
        scope.launch {
            runCatching { autoRestoreIfEmpty() }.onFailure { Log.w(TAG, "Automatic restore failed: ${it.message}") }
        }
    }

    // ---------- Actions ----------

    suspend fun backupNow(force: Boolean): Result<Unit> = runCatching {
        val api = client ?: error("Backup isn't set up in this build")
        val account = authManager.authState.value as? AuthState.FullAccount
            ?: error("Sign in to Nuvio to back up")
        val token = auth.currentSessionOrNull()?.accessToken?.takeIf { it.isNotBlank() }
            ?: error("No Nuvio sign-in on this device")
        val response = api.post(
            path = "/v1/sync",
            body = deviceBody {
                store.deviceKey()?.let { put("device_key", it) }
                if (force) put("force", true)
            },
            bearer = token
        )
        response["device_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            store.saveDeviceKey(it, account.email)
        } ?: store.saveAccount(account.email)
        store.markBackup()
        Log.d(TAG, "Backup saved (skipped=${response["skipped"]}, changed=${response["changed"]})")
    }

    suspend fun ping() {
        val api = client ?: return
        val key = store.deviceKey() ?: return
        try {
            api.post("/v1/ping", deviceBody(), bearer = key, deviceId = store.deviceId)
        } catch (e: KableKillersBackupHttpException) {
            if (e.status == 401) store.clearDeviceKey()
            throw e
        }
    }

    /** New device while Nuvio is down: prove the login with the owner's restore PIN. */
    suspend fun signInWithRestorePin(email: String, pin: String): Result<Unit> = runCatching {
        val api = client ?: error("Backup isn't set up in this build")
        val response = api.post(
            path = "/v1/pin-login",
            body = deviceBody {
                put("email", email.trim().lowercase())
                put("pin", pin.trim())
            }
        )
        val key = response["device_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Backup server didn't return a device key")
        store.saveDeviceKey(key, response["account_email"]?.jsonPrimitive?.contentOrNull ?: email.trim().lowercase())
    }

    suspend fun restoreNow(): Result<KableKillersRestoreSummary> = runCatching {
        restoreMutex.withLock {
            val api = client ?: error("Backup isn't set up in this build")
            val key = store.deviceKey() ?: error("This device isn't connected to the backup yet")
            if (profileManager.activeProfileId.value != 1) error("Switch to Profile 1 to restore")

            val text = try {
                api.get("/v1/config", bearer = key, deviceId = store.deviceId)
            } catch (e: KableKillersBackupHttpException) {
                if (e.status == 401) store.clearDeviceKey()
                throw e
            }
            val payload = json.decodeFromString(KableKillersBackupPayload.serializer(), text)
            check(payload.schema == "kk-backup-v1") { "Unknown backup format" }
            val signedInEmail = (authManager.authState.value as? AuthState.FullAccount)?.email
            if (signedInEmail != null && !signedInEmail.equals(payload.accountEmail, ignoreCase = true)) {
                error("This backup belongs to ${payload.accountEmail}, not $signedInEmail")
            }
            apply(payload).also {
                store.markRestore()
                Log.i(TAG, "Restored Profile 1 from backup: $it")
            }
        }
    }

    // ---------- Internals ----------

    private suspend fun autoRestoreIfEmpty() {
        if (store.deviceKey() == null) return
        if (profileManager.activeProfileId.value != 1) return
        val now = System.currentTimeMillis()
        if (now - lastAutoRestoreMs < AUTO_RESTORE_MIN_INTERVAL_MS) return
        if (addonPreferences.installedAddonUrls.first().isNotEmpty()) return
        lastAutoRestoreMs = now
        Log.i(TAG, "Nuvio unreachable and Profile 1 has no add-ons; restoring from backup")
        restoreNow().getOrThrow()
    }

    private suspend fun apply(payload: KableKillersBackupPayload): KableKillersRestoreSummary {
        val surfaces = payload.surfaces
        val cloudProfiles = !payload.accountEmail.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)

        val profilesRestored = if (surfaces.profiles.isNotEmpty()) {
            profileSyncService.restoreProfilesFromBackup(surfaces.profiles, cloudProfiles)
            if (cloudProfiles) surfaces.profiles.size else 1
        } else 0

        // Empty lists never wipe what's on the device.
        var addons = 0
        if (surfaces.addons.isNotEmpty()) {
            addonRepository.isSyncingFromRemote = true
            try {
                val urls = addonSyncService.applyBackupAddonRows(surfaces.addons)
                addonRepository.reconcileWithRemoteAddonUrls(remoteUrls = urls, removeMissingLocal = true)
                addons = urls.size
            } finally {
                addonRepository.isSyncingFromRemote = false
            }
        }

        var plugins = 0
        val pluginRows = surfaces.plugins.orEmpty()
        if (pluginRows.isNotEmpty()) {
            pluginManager.isSyncingFromRemote = true
            try {
                val remote = pluginRows.sortedBy { it.sortOrder }.map { RemotePluginInfo(url = it.url, repoType = it.repoType) }
                pluginManager.reconcileWithRemoteRepoUrls(remotePlugins = remote, removeMissingLocal = true)
                plugins = remote.size
            } finally {
                pluginManager.isSyncingFromRemote = false
                pluginManager.flushPendingSync()
            }
        }

        var collections = 0
        surfaces.collections.firstOrNull()?.collectionsJson?.let { element ->
            val restored = collectionsDataStore.importFromJson(element.toString())
            if (restored.isNotEmpty()) {
                collectionSyncService.isSyncingFromRemote = true
                try {
                    collectionsDataStore.setCollections(restored)
                } finally {
                    collectionSyncService.isSyncingFromRemote = false
                }
                collections = restored.size
            }
        }

        var homeRows = 0
        surfaces.homeCatalogSettings.firstOrNull()?.settingsJson?.let { settings ->
            val home = runCatching { json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settings) }.getOrNull()
            if (home != null && home.items.isNotEmpty()) {
                homeCatalogSettingsSyncService.isSyncingFromRemote = true
                try {
                    layoutPreferenceDataStore.applyCatalogSettingsFromRemote(home)
                } finally {
                    homeCatalogSettingsSyncService.isSyncingFromRemote = false
                }
                homeRows = home.items.size
            }
        }

        val settingsBlob: JsonObject? = surfaces.profileSettingsTv?.firstOrNull()?.settingsJson
        val settings = settingsBlob != null && profileSettingsSyncService.restoreProfileOneFromBackup(settingsBlob)

        return KableKillersRestoreSummary(addons, plugins, collections, homeRows, settings, profilesRestored)
    }

    private fun deviceBody(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("device_id", store.deviceId)
        put("device_name", deviceName())
        put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("platform", "tv")
        put("app_version", BuildConfig.VERSION_NAME)
        extra()
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
