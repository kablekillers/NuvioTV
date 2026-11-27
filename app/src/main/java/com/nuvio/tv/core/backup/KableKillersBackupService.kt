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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KableKillersBackup"
private const val SYNC_MIN_INTERVAL_MS = 10 * 60_000L
private const val AUTO_RESTORE_MIN_INTERVAL_MS = 60 * 60_000L
private const val FOLLOW_INTERVAL_MS = 2 * 60_000L
private const val SEND_CHECK_INTERVAL_MS = 30_000L
private const val VPN_CHECK_INTERVAL_MS = 30 * 60_000L

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
    private val pluginSyncService: com.nuvio.tv.core.sync.PluginSyncService,
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
    /** Nuvio's last sync failed, so the backup is the source of truth until it works again. */
    @Volatile private var nuvioUnreachable = false
    @Volatile private var loopsStarted = false

    val enabled: Boolean get() = client != null
    val state = store.state

    // ---------- Hooks called by StartupSyncService ----------

    fun onAppStart() {
        if (!enabled) return
        scope.launch { runCatching { ping() }.onFailure { Log.w(TAG, "Backup check-in failed: ${it.message}") } }
        startLoops()
    }

    /**
     * While Nuvio is down (or this device signed in through the backup), act like Nuvio:
     * - every couple of minutes, apply any newer copy of Profile 1 from the backup (household
     *   profiles read Profile 1's add-ons, plugins and rows, so they follow too);
     * - when Profile 1 was changed on this device and this device may change it (owner login, or
     *   unlocked with the restore PIN on the customer login), send the change to the backup.
     */
    private fun startLoops() {
        if (loopsStarted) return
        loopsStarted = true
        scope.launch {
            while (true) {
                if (backupIsSourceOfTruth()) {
                    runCatching { followBackup() }.onFailure { Log.w(TAG, "Following the backup failed: ${it.message}") }
                }
                delay(FOLLOW_INTERVAL_MS)
            }
        }
        // Built-in VPN: this TV's config from the owner's pool (on start, then every 30 minutes).
        com.nuvio.tv.core.vpn.KkVpn.poolSync = { scope.launch { runCatching { syncVpnFromPool() } } }
        scope.launch {
            delay(10_000)
            while (true) {
                runCatching { syncVpnFromPool() }.onFailure { Log.w(TAG, "VPN pool check failed: ${it.message}") }
                delay(VPN_CHECK_INTERVAL_MS)
            }
        }
        scope.launch {
            while (true) {
                delay(SEND_CHECK_INTERVAL_MS)
                if (backupIsSourceOfTruth() && canChangeProfileOne()) {
                    runCatching { sendProfileOneIfChanged() }.onFailure { Log.w(TAG, "Sending Profile 1 to the backup failed: ${it.message}") }
                }
            }
        }
    }

    private fun backupIsSourceOfTruth(): Boolean =
        enabled && store.deviceKey() != null &&
            (nuvioUnreachable || authManager.authState.value !is AuthState.FullAccount)

    /** The account this device belongs to on the backup (its Nuvio login, or the one it was approved for). */
    fun backupAccountEmail(): String? =
        (authManager.authState.value as? AuthState.FullAccount)?.email?.lowercase() ?: store.state.value.accountEmail?.lowercase()

    /** On the customer login, Profile 1 needs the restore PIN while Nuvio is down; the owner's own login doesn't. */
    fun canChangeProfileOne(): Boolean {
        val email = backupAccountEmail() ?: return false
        if (!email.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)) return true
        return store.editToken() != null
    }

    /** Device signed in only through the backup (no Nuvio sign-in) on the customer login. */
    fun isBackupOnlyCustomerDevice(): Boolean =
        enabled && store.deviceKey() != null && authManager.authState.value !is AuthState.FullAccount &&
            store.state.value.accountEmail.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)

    /** Restore PIN unlock for Profile 1 while Nuvio can't check its own PIN. */
    suspend fun unlockWithRestorePin(pin: String): Result<Unit> = runCatching {
        val api = client ?: error("Backup isn't set up in this build")
        val key = store.deviceKey() ?: error("This device isn't connected to the backup yet")
        val response = api.post("/v1/unlock", buildJsonObject { put("pin", pin.trim()) }, bearer = key, deviceId = store.deviceId)
        val token = response.string("edit_token") ?: error("Backup server didn't return an unlock")
        store.saveEditToken(token, response.long("expires_at") ?: (System.currentTimeMillis() / 1000 + 3600))
    }

    /** Applies the backup's copy if it's newer than what this device has. */
    private suspend fun followBackup() {
        val api = client ?: return
        val key = store.deviceKey() ?: return
        val response = try {
            api.post("/v1/ping", deviceBody(), bearer = key, deviceId = store.deviceId)
        } catch (e: KableKillersBackupHttpException) {
            if (e.status == 401) store.clearDeviceKey()
            throw e
        }
        val latest = response.long("snapshot_id") ?: return
        if (latest != store.lastAppliedSnapshotId) applyLatest()
    }

    private suspend fun applyLatest(): KableKillersRestoreSummary = restoreMutex.withLock {
        val api = client ?: error("Backup isn't set up in this build")
        val key = store.deviceKey() ?: error("This device isn't connected to the backup yet")
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
        val summary = apply(payload)
        payload.snapshotId?.let { store.lastAppliedSnapshotId = it }
        store.localBaseline = runCatching { fingerprint(localProfileOneSurfaces()) }.getOrNull()
        store.markRestore()
        Log.i(TAG, "Applied backup copy ${payload.snapshotId}: $summary")
        summary
    }

    /** Builds Profile 1's setup from this device, in the same shape Nuvio stores it. */
    private suspend fun localProfileOneSurfaces(): JsonObject {
        val urls = addonPreferences.installedAddonUrls.first()
        val names = addonPreferences.userSetNames.first()
        val enabledStates = addonPreferences.addonEnabledStates.first()
        val repos = pluginManager.repositories.first()
        val collectionsJson = collectionsDataStore.exportCurrentProfileJson()
        val home = homeCatalogSettingsSyncService.localPayloadForBackup()
        val settings = profileSettingsSyncService.exportProfileOneForBackup()
        return buildJsonObject {
            put("addons", buildJsonArray {
                urls.forEachIndexed { index, url ->
                    val canonical = canonicalAddonUrl(url)
                    addJsonObject {
                        put("url", url)
                        put("sort_order", index)
                        put("enabled", enabledStates[canonical] ?: true)
                        (names[canonical] ?: names[url])?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                    }
                }
            })
            put("plugins", buildJsonArray {
                repos.forEachIndexed { index, repo ->
                    addJsonObject {
                        put("url", repo.url)
                        put("name", repo.name)
                        put("enabled", repo.enabled)
                        put("sort_order", index)
                        put("repo_type", repo.type.name)
                    }
                }
            })
            put("collections", buildJsonArray {
                addJsonObject {
                    put("profile_id", 1)
                    put("collections_json", collectionsJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonArray(emptyList()))
                }
            })
            put("home_catalog_settings", buildJsonArray {
                addJsonObject {
                    put("profile_id", 1)
                    put("settings_json", json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), home))
                }
            })
            put("profile_settings_tv", buildJsonArray {
                addJsonObject {
                    put("profile_id", 1)
                    put("settings_json", settings)
                }
            })
        }
    }

    private fun fingerprint(surfaces: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(surfaces.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalAddonUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith("/manifest.json", ignoreCase = true)) trimmed.dropLast("/manifest.json".length) else trimmed
    }

    /** Owner changed Profile 1 on this device while Nuvio is down: send it to the backup. */
    private suspend fun sendProfileOneIfChanged() {
        if (profileManager.activeProfileId.value != KableKillersAccountPolicy.PRIMARY_PROFILE_ID) return
        val api = client ?: return
        val key = store.deviceKey() ?: return
        if (restoreMutex.isLocked) return
        val surfaces = localProfileOneSurfaces()
        val print = fingerprint(surfaces)
        val baseline = store.localBaseline
        if (baseline == null) {
            // Nothing to compare with yet (first run on this version): just remember where we are.
            store.localBaseline = print
            return
        }
        if (print == baseline) return
        val response = api.post(
            path = "/v1/push",
            body = buildJsonObject {
                put("surfaces", surfaces)
                store.editToken()?.let { put("edit_token", it) }
            },
            bearer = key,
            deviceId = store.deviceId
        )
        store.localBaseline = print
        response.long("snapshot_id")?.let { store.lastAppliedSnapshotId = it }
        store.markBackup()
        Log.i(TAG, "Sent Profile 1 changes to the backup (held_back=${response["held_back"]})")
    }

    fun onNuvioSyncSucceeded() {
        nuvioUnreachable = false
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAttemptMs < SYNC_MIN_INTERVAL_MS) return
        lastSyncAttemptMs = now
        scope.launch { backupNow(force = false).onFailure { Log.w(TAG, "Backup after Nuvio sync failed: ${it.message}") } }
    }

    /** Nuvio couldn't be reached at all. If Profile 1 is empty on this device, fill it from the backup. */
    fun onNuvioSyncFailed() {
        nuvioUnreachable = true
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
                put("applied_snapshot_id", store.lastAppliedSnapshotId)
                put("can_upload", profileManager.activeProfileId.value == KableKillersAccountPolicy.PRIMARY_PROFILE_ID)
            },
            bearer = token
        )
        response["device_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            store.saveDeviceKey(it, account.email)
        } ?: store.saveAccount(account.email)
        store.markBackup()
        Log.d(TAG, "Backup saved (skipped=${response["skipped"]}, changed=${response["changed"]})")
        handleOutageChanges(response, token)
    }

    /**
     * Nuvio is back, but Profile 1 was changed on the backup while it was down, so Nuvio's copy is old
     * (and this device's Nuvio pull just brought the old setup back). Put the backup's copy back on this
     * device; if the backup picked this device, send it up to Nuvio too.
     */
    private suspend fun handleOutageChanges(response: JsonObject, token: String) {
        val outage = response["outage_changes"] as? JsonObject ?: return
        val snapshotId = outage.long("snapshot_id") ?: return
        applyLatest()
        if (outage["upload"]?.jsonPrimitive?.contentOrNull != "true") return
        if (profileManager.activeProfileId.value != KableKillersAccountPolicy.PRIMARY_PROFILE_ID) return
        Log.i(TAG, "Sending Profile 1 changes made during the Nuvio outage up to Nuvio")
        addonSyncService.pushToRemote().getOrThrow()
        pluginSyncService.pushToRemote().getOrThrow()
        collectionSyncService.pushToRemote().getOrThrow()
        homeCatalogSettingsSyncService.pushToRemote("kk-backup-outage-changes").getOrThrow()
        profileSettingsSyncService.pushCurrentProfileToRemote().getOrThrow()
        val api = client ?: return
        api.post(
            path = "/v1/sync",
            body = deviceBody {
                store.deviceKey()?.let { put("device_key", it) }
                put("force", true)
                put("uploaded_snapshot_id", snapshotId)
            },
            bearer = token
        )
        Log.i(TAG, "Nuvio has the outage changes again")
    }

    /**
     * Built-in VPN from the owner's pool (same as Kable Killers Mobile): this TV gets its own
     * IPVanish WireGuard config the first time it asks and keeps it. A config added by hand from a
     * phone is never replaced. If the owner took the config back, it is removed from this TV.
     */
    suspend fun syncVpnFromPool() {
        val api = client ?: return
        val key = store.deviceKey() ?: return
        val vpn = com.nuvio.tv.core.vpn.KkVpn
        val current = vpn.state.value
        if (current.hasProfile && current.source != "pool") return
        val response = try {
            api.post("/v1/vpn/claim", buildJsonObject { }, bearer = key, deviceId = store.deviceId)
        } catch (e: KableKillersBackupHttpException) {
            when (e.status) {
                404, 401 -> if (vpn.state.value.source == "pool") vpn.remove()
            }
            return
        }
        val config = response.string("config") ?: return
        val error = vpn.importFromPool(config)
        if (error != null) Log.w(TAG, "VPN config from the pool wasn't usable: $error")
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

    /** Nuvio can't hand out a QR: ask the backup for one. The owner scans it and approves the device. */
    suspend fun startBackupQr(): Result<KableKillersBackupQr> = runCatching {
        val api = client ?: error("Backup isn't set up in this build")
        val response = api.post(path = "/v1/qr/start", body = deviceBody())
        KableKillersBackupQr(
            code = response.string("code") ?: error("Backup server didn't return a QR code"),
            pollToken = response.string("poll_token") ?: error("Backup server didn't return a QR code"),
            url = response.string("url") ?: error("Backup server didn't return a QR code"),
            expiresInSeconds = response.long("expires_in") ?: 1800L,
            pollIntervalSeconds = (response.long("poll_interval") ?: 3L).toInt()
        )
    }

    /**
     * Checks a backup QR. Once the owner approves it, saves this device's backup key
     * and fills Profile 1 from the backup, then reports [KableKillersBackupQrStatus.SignedIn].
     */
    suspend fun pollBackupQr(qr: KableKillersBackupQr): Result<KableKillersBackupQrStatus> = runCatching {
        val api = client ?: error("Backup isn't set up in this build")
        val response = api.post(
            path = "/v1/qr/poll",
            body = buildJsonObject {
                put("code", qr.code)
                put("poll_token", qr.pollToken)
            }
        )
        when (response.string("status")) {
            "approved" -> {
                val key = response.string("device_key") ?: error("Backup server didn't return a device key")
                store.saveDeviceKey(key, response.string("account_email"))
                // Signed in either way; the setup can also be restored later from Account settings.
                val summary = runCatching { applyLatest() }
                    .onFailure { Log.w(TAG, "Signed in with backup QR, but restore failed: ${it.message}") }
                    .getOrNull()
                KableKillersBackupQrStatus.SignedIn(summary)
            }
            "denied" -> KableKillersBackupQrStatus.Denied
            "expired", "used" -> KableKillersBackupQrStatus.Expired
            else -> KableKillersBackupQrStatus.Waiting
        }
    }

    /** The person signed out on this device: forget its backup key so getting back in takes a new QR. */
    fun forgetDevice() {
        store.clearDeviceKey()
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
                payload.snapshotId?.let { id -> store.lastAppliedSnapshotId = id }
                store.localBaseline = runCatching { fingerprint(localProfileOneSurfaces()) }.getOrNull()
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

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
