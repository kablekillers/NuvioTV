package com.nuvio.tv.core.backup

import com.nuvio.tv.data.remote.supabase.SupabaseAddon
import com.nuvio.tv.data.remote.supabase.SupabaseCollectionBlob
import com.nuvio.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import com.nuvio.tv.data.remote.supabase.SupabasePlugin
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfileSettingsBlob
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the Kable Killers backup Worker lives. Blank turns the backup off.
 * Deployed from cloudflare/kk-backup.
 */
object KableKillersBackupConfig {
    const val BASE_URL: String = "https://kable-killers-backup.kablekillers6.workers.dev"
}

/** A saved copy of a login's Profile 1 setup, in the same shape Nuvio returns it. */
@Serializable
data class KableKillersBackupPayload(
    val schema: String = "",
    @SerialName("account_email") val accountEmail: String = "",
    @SerialName("snapshot_id") val snapshotId: Long? = null,
    @SerialName("saved_at") val savedAt: Long? = null,
    val surfaces: KableKillersBackupSurfaces = KableKillersBackupSurfaces()
)

@Serializable
data class KableKillersBackupSurfaces(
    val addons: List<SupabaseAddon> = emptyList(),
    val plugins: List<SupabasePlugin>? = null,
    val collections: List<SupabaseCollectionBlob> = emptyList(),
    @SerialName("home_catalog_settings") val homeCatalogSettings: List<SupabaseHomeCatalogSettingsBlob> = emptyList(),
    @SerialName("profile_settings_tv") val profileSettingsTv: List<SupabaseProfileSettingsBlob>? = null,
    val profiles: List<SupabaseProfile> = emptyList()
)

data class KableKillersRestoreSummary(
    val addons: Int,
    val plugins: Int,
    val collections: Int,
    val homeRows: Int,
    val settings: Boolean,
    val profiles: Int
)

data class KableKillersBackupState(
    val hasDeviceKey: Boolean = false,
    val accountEmail: String? = null,
    val lastBackupAtMs: Long = 0L,
    val lastRestoreAtMs: Long = 0L
)

/** A backup QR shown while Nuvio is down. */
data class KableKillersBackupQr(
    val code: String,
    val pollToken: String,
    val url: String,
    val expiresInSeconds: Long,
    val pollIntervalSeconds: Int
)

sealed interface KableKillersBackupQrStatus {
    data object Waiting : KableKillersBackupQrStatus
    data object Denied : KableKillersBackupQrStatus
    data object Expired : KableKillersBackupQrStatus
    data class SignedIn(val summary: KableKillersRestoreSummary?) : KableKillersBackupQrStatus
}
