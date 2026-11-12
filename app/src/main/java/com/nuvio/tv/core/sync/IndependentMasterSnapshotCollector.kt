package com.nuvio.tv.core.sync

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects the three master configuration surfaces into app-private storage.
 *
 * This is intentionally NOT invoked by startup, remote pulls, or user changes yet.
 * Only the primary profile is eligible. No account identifiers, history or credentials.
 * Future integration must guard against profile changes during suspension.
 */
@Singleton
internal class IndependentMasterSnapshotCollector @Inject constructor(
    private val profiles: ProfileManager,
    private val addons: AddonPreferences,
    private val collections: CollectionsDataStore,
    private val layout: LayoutPreferenceDataStore,
    private val addonRepository: AddonRepository,
    private val store: IndependentMasterSnapshotStore,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun capturePrimaryProfile(): Boolean {
        val active = profiles.activeProfile ?: return false
        if (!active.isPrimary) return false
        val profileId = profiles.activeProfileId.value

        val urls = addons.installedAddonUrls.first()
        val enabled = addons.addonEnabledStates.first()
        val names = addons.userSetNames.first()
        val collectionJson = collections.exportCurrentProfileJson() ?: "[]"
        val collectionList = json.parseToJsonElement(collectionJson)
        val catalogAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        val currentCollections = collections.getCurrentCollections()
        val homePayload = layout.exportCatalogSettingsToSyncPayload(catalogAddons, currentCollections)

        // A profile switch during suspend points must not leak another profile into the master.
        if (profiles.activeProfileId.value != profileId || profiles.activeProfile?.isPrimary != true) {
            return false
        }
        val snapshot = buildJsonObject {
            put("schema", IndependentMasterSnapshotStore.SCHEMA)
            put("addons", buildJsonArray {
                urls.forEachIndexed { index, url ->
                    addJsonObject {
                        put("url", url)
                        put("sort_order", index)
                        put("enabled", enabled[url] ?: true)
                        names[url]?.let { put("name", it) }
                    }
                }
            })
            put("collections", collectionList)
            put("home_catalog_settings", json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), homePayload))
        }
        store.save(snapshot)
        return true
    }
}
